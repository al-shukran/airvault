package app.lensly.core.vault

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Persists [FailureState] as two copies in the vault directory: `failures.bin`, then `failures.bak`. Each copy is the state
 * followed by an HMAC-SHA256 made with the Keystore key for that state's sequence number (see
 * [KeystoreBackend.createFailureMacKey]). The primary copy is always written first.
 *
 * A save creates the key for its new sequence number, writes both copies atomically (temp, fsync, rename), and only then
 * deletes every other Delay State key. So a copy verifies only while its own key exists. A restored older pair names a key
 * that was deleted, so it counts as tampered: it gets the strictest cap, and a copy that still verifies keeps its own cap
 * (see [tamperedCap]). Tampering never lowers the owner's cap. Within this process, a copy older than the last save is also
 * tampered, even if its key still exists.
 *
 * The cap is part of the MAC-protected payload, so it is read before any unlock and cannot be changed in the file without
 * detection. It lives here rather than in the encrypted vault settings, because the delay applies before the vault can be
 * unlocked.
 *
 * The boot count (`Settings.Global.BOOT_COUNT`, or the marker for "not reported") is in the same payload. A later load compares
 * it with the device's current count, so a reboot, which restarts the monotonic clock, still measures the wait from where the
 * state left it and never shortens it (see [FailureDelay.elapsedSinceFailure]).
 *
 * Copies written before per-save keys (layout versions 1 and 2) verify under the one legacy key. [load] settles such a pair
 * by saving it again under a new sequence number, which deletes the legacy key. The pending delay is kept.
 *
 * Assumptions and residual risk:
 * - A deleted Keystore key cannot be brought back. This class cannot prove it. It rests on KeyMint: the StrongBox guarantee
 *   the owner accepted, and whatever the TEE provides. The public SDK offers no rollback-resistance flag for app keys, so the
 *   app cannot request the guarantee. It records the key's security level instead; that level is logged when a key is made.
 * - Crash window: between writing the new pair and deleting the previous key, the previous key still exists. If power is cut
 *   there and an attacker restores the copies from before the save, the previous state verifies again until the next load
 *   deletes its key. That is one step of rollback per interrupted save.
 * - The directory is not fsynced after a rename. A power cut right after a save can leave the previous pair with its key
 *   already deleted, which reads as tampered: the owner's cap is kept when one copy verifies, and otherwise the strictest cap.
 * - A failed check whose state cannot be written is not counted. Keystore errors surface as [VaultException].
 */
internal class FailureStore(
    private val dir: File,
    private val keys: KeystoreBackend,
    private val readBootCount: () -> Int? = { null },
    private val readBootCount: () -> Int? = { null },
    private val write: (File, ByteArray) -> Unit = ::writeAtomically,
) {
    private val primary = File(dir, PRIMARY_FILE_NAME)
    private val backup = File(dir, BACKUP_FILE_NAME)
    private val seenKey = dir.absolutePath

    /** A copy that verified. [legacy] is true for a layout written before per-save keys. */
    private class Copy(val state: FailureState, val legacy: Boolean)

    /** The device's current `Settings.Global.BOOT_COUNT`, or null when it could not be read. */
    fun currentBootCount(): Int? = readBootCount()

    /** Loads the stored state, or returns the cap-locking state if the copies look tampered. */
    fun load(clock: Clock): FailureState {
        val p = readCopy(primary)
        val b = readCopy(backup)
        val seen = SEQ_SEEN[seenKey] ?: 0L
        if (p != null && b != null && p.state.seq >= b.state.seq && p.state.seq >= seen && !wallMovedBack(p.state, clock)) {
            // A legacy copy, or copies that are not one save apart, means a save or a migration was interrupted. Saving the
            // state again under a new sequence number settles both copies and deletes the keys they no longer use.
            if (p.legacy || b.legacy || p.state.seq != b.state.seq) return save(p.state.copy(seq = p.state.seq + 1), clock)
            // Keys left behind by an interrupted save are deleted here.
            keystore(STATE_UNAVAILABLE) { keys.deleteFailureMacKeysExcept(p.state.seq) }
            return p.state
        }
        // A copy verifies when its MAC checks out under its own key and it is not older than this process has seen.
        val verified = listOfNotNull(p, b).map { it.state }.filter { it.seq >= seen }
        return lockAsTampered(clock, highestSeq = maxOf(p?.state?.seq ?: 0L, b?.state?.seq ?: 0L, seen), verified = verified)
    }

    /** Creates the initial state for a new vault: no failures, sequence 1. */
    fun initialize(clock: Clock) {
        save(
            FailureState(
                seq = 1,
                failures = 0,
                lastFailureWallMs = clock.wallMillis(),
                lastFailureElapsedMs = clock.elapsedMillis(),
            ),
            clock,
        )
    }

    /** Records one more failed check, made at [clock]'s current time, and returns the new state. */
    fun recordFailure(previous: FailureState, clock: Clock): FailureState =
        save(
            previous.copy(
                seq = previous.seq + 1,
                failures = minOf(previous.failures + 1, MAX_FAILURES),
                lastFailureWallMs = clock.wallMillis(),
                lastFailureElapsedMs = clock.elapsedMillis(),
            ),
            clock,
        )

    /**
     * Clears the failure count after a passing check. The state is written even when there was nothing to clear, so a
     * passing check costs the same two writes and fsyncs as a failed one and cannot be told apart by its storage work.
     */
    fun recordSuccess(previous: FailureState, clock: Clock): FailureState {
        val reset = if (previous.failures == 0) previous else previous.copy(seq = previous.seq + 1, failures = 0)
        return save(reset, clock)
    }

    /**
     * Saves the Failure Delay cap the owner chose, or null for Default, and returns the new state. The failure count
     * is left as it is. The caller has already checked the App Password.
     */
    fun recordCap(previous: FailureState, capMs: Long?, clock: Clock): FailureState {
        if (capMs != null) {
            require(capMs in FailureDelay.MIN_CAP_MS..FailureDelay.MAX_CAP_MS) { "cap must be between 1 s and 1 h" }
        }
        return save(previous.copy(seq = previous.seq + 1, capMs = capMs), clock)
    }

    private fun wallMovedBack(state: FailureState, clock: Clock): Boolean =
        state.failures > 0 && FailureDelay.elapsedSinceFailure(state, clock, readBootCount()) < 0

    private fun lockAsTampered(clock: Clock, highestSeq: Long, verified: List<FailureState>): FailureState =
        // Saving writes both copies, so a copy that was damaged is repaired here.
        save(
            FailureState(
                seq = highestSeq + 1,
                failures = TAMPER_FAILURES,
                lastFailureWallMs = clock.wallMillis(),
                lastFailureElapsedMs = clock.elapsedMillis(),
                capMs = tamperedCap(verified),
            ),
            clock,
        )

    /**
     * The cap a tampered state keeps, so tampering never weakens the owner's protection. One verified copy: its own cap,
     * and the other copy is repaired. Two verified copies that disagree: the stricter of the two, so a rolled-back copy
     * cannot lower the cap. No verified copy: the strictest cap allowed, one hour, so corrupting both files cannot lower
     * the owner's cap.
     */
    private fun tamperedCap(verified: List<FailureState>): Long? = when (verified.size) {
        0 -> FailureDelay.MAX_CAP_MS
        1 -> verified.single().capMs
        else -> verified.maxBy { FailureDelay.configuredCapFor(it) }.capMs
    }

    /**
     * Writes [state] under a new key for its sequence number, then deletes every other Delay State key. The keys of earlier
     * states go only after both copies carry the new one, so a crash leaves at least one key for each copy. The boot count and
     * the time the wait had already run are recorded with the state, so a reboot continues the wait instead of restarting it.
     */
    private fun save(state: FailureState, clock: Clock): FailureState {
        dir.mkdirs()
        val stamped = stamped(state, clock)
        val key = keystore(STATE_UNAVAILABLE) { keys.createFailureMacKey(stamped.seq) }
        val bytes = encode(stamped, key)
        write(primary, bytes)
        write(backup, bytes)
        SEQ_SEEN[seenKey] = stamped.seq
        keystore(STATE_UNAVAILABLE) { keys.deleteFailureMacKeysExcept(stamped.seq) }
        return stamped
    }

    /**
     * [state] with the readings that are only known now: the device's boot count, and how much of the delay had run when the
     * state is written. A later load compares the boot count and continues the wait from that reading. The reading is never
     * below zero, because a wall clock that went back must not shorten the wait after a reboot.
     */
    private fun stamped(state: FailureState, clock: Clock): FailureState {
        val bootCount = readBootCount()
        val elapsed = if (state.failures == 0) 0L else FailureDelay.elapsedSinceFailure(state, clock, bootCount)
        return state.copy(bootCount = bootCount, elapsedAtSave = maxOf(0L, elapsed))
    }

    /** The copy at [file] if it verifies under its own key, or null. A missing or unreadable file is not a copy. */
    private fun readCopy(file: File): Copy? {
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            return null
        }
        if (bytes.size < MAGIC.size + 1 || !bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) return null
        val version = bytes[MAGIC.size]
        val payloadSize = when (version) {
            VERSION_1 -> PAYLOAD_SIZE_V1
            VERSION_2 -> PAYLOAD_SIZE_V2
            VERSION -> PAYLOAD_SIZE
            else -> return null
        }
        if (bytes.size != payloadSize + MAC_SIZE) return null
        val payload = bytes.copyOfRange(0, payloadSize)
        val mac = bytes.copyOfRange(payloadSize, bytes.size)
        val key = keystore(STATE_UNAVAILABLE) {
            // Version 3 names its key by sequence number, which is read before the MAC is checked. A wrong number names no key,
            // or another one, so the MAC then fails.
            if (version == VERSION) keys.failureMacKey(ByteBuffer.wrap(payload).getLong(MAGIC.size + 1)) else keys.legacyFailureMacKey()
        } ?: return null
        if (!MessageDigest.isEqual(mac, keystore(STATE_UNAVAILABLE) { macOf(key, payload) })) return null
        return parse(payload, version)
    }

    private fun parse(payload: ByteArray, version: Byte): Copy? {
        val buffer = ByteBuffer.wrap(payload).apply { position(MAGIC.size + 1) }
        val seq = buffer.getLong()
        val failures = buffer.getInt()
        val lastFailureWallMs = buffer.getLong()
        val lastFailureElapsedMs = buffer.getLong()
        if (version == VERSION_1) {
            // Written before the cap existed, so the owner had not chosen one.
            return Copy(FailureState(seq, failures, lastFailureWallMs, lastFailureElapsedMs), legacy = true)
        }
        val capMs = when (val stored = buffer.getLong()) {
            NO_CAP_ON_DISK -> null
            // A MAC-valid cap outside the offered range is not something this app writes, so the copy is not trusted.
            in FailureDelay.MIN_CAP_MS..FailureDelay.MAX_CAP_MS -> stored
            else -> return null
        }
        // Version 2 is the layout from before the boot count, so it has none.
        val bootCount = if (version == VERSION_2) null else bootCountOf(buffer.getInt())
        val elapsedAtSave = if (version == VERSION_2) 0L else buffer.getLong()
        return Copy(
            FailureState(seq, failures, lastFailureWallMs, lastFailureElapsedMs, capMs, bootCount, elapsedAtSave),
            legacy = version != VERSION,
        )
    }

    private fun encode(state: FailureState, key: SecretKey): ByteArray {
        val payload = ByteBuffer.allocate(PAYLOAD_SIZE)
            .put(MAGIC)
            .put(VERSION)
            .putLong(state.seq)
            .putInt(state.failures)
            .putLong(state.lastFailureWallMs)
            .putLong(state.lastFailureElapsedMs)
            .putLong(state.capMs ?: NO_CAP_ON_DISK)
            .putInt(state.bootCount ?: NO_BOOT_COUNT)
            .putLong(state.elapsedAtSave)
            .array()
        return payload + keystore(STATE_UNAVAILABLE) { macOf(key, payload) }
    }

    private fun macOf(key: SecretKey, payload: ByteArray): ByteArray {
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(key)
        return mac.doFinal(payload)
    }

    companion object {
        const val PRIMARY_FILE_NAME = "failures.bin"
        const val BACKUP_FILE_NAME = "failures.bak"

        /** Failure count used when tampering is detected. Any count from 41 on gives the full cap. */
        const val TAMPER_FAILURES = 64

        /** Upper bound on the stored failure count, so the count cannot overflow. */
        private const val MAX_FAILURES = 1_000_000

        private const val MAC_ALGORITHM = "HmacSHA256"
        private val MAGIC = "LNFS".toByteArray(Charsets.US_ASCII)

        /**
         * Version 1 has no cap. Version 2 adds the cap, and both use the legacy key. Version 3 has the same fields as version 2,
         * plus the boot count and the time the wait had run at the save, and it is MAC'd under the key for its sequence number.
         */
        private const val VERSION_1: Byte = 1
        private const val VERSION_2: Byte = 2
        private const val VERSION: Byte = 3

        /** The cap on disk when the owner chose Default. Zero is outside the offered range, so it cannot be a real cap. */
        private const val NO_CAP_ON_DISK = 0L

        /** The boot count on disk when the device did not report one. A real count is never negative. */
        private const val NO_BOOT_COUNT = -1

        /** magic (4) + version (1) + seq (8) + failures (4) + wall (8) + elapsed (8). */
        private const val PAYLOAD_SIZE_V1 = 4 + 1 + 8 + 4 + 8 + 8

        /** Version 1 payload plus the cap (8). */
        private const val PAYLOAD_SIZE_V2 = PAYLOAD_SIZE_V1 + 8

        /** Version 2 payload plus the boot count (4) and the time the wait had run at the save (8). */
        private const val PAYLOAD_SIZE = PAYLOAD_SIZE_V2 + 4 + 8
        private const val MAC_SIZE = 32

        private const val STATE_UNAVAILABLE = "The Failure Delay state could not be read or written through the Keystore."

        /** The boot count read from the record, or null for the marker this app writes when it had none. */
        private fun bootCountOf(stored: Int): Int? = if (stored == NO_BOOT_COUNT) null else stored

        /** Highest sequence number this process has written, keyed by vault directory. */
        private val SEQ_SEEN = ConcurrentHashMap<String, Long>()

        /** Forgets the in-process sequence number for [dir]. Call this when the vault is wiped. */
        fun forget(dir: File) {
            SEQ_SEEN.remove(dir.absolutePath)
        }
    }
}
