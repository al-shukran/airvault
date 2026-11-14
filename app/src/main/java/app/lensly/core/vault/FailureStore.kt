package app.lensly.core.vault

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.ProviderException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Persists [FailureState] as two copies in the vault directory: `failures.bin`, then `failures.bak`. Each copy is the state
 * followed by an HMAC-SHA256 made with the Keystore key for that state's sequence number (see
 * [KeystoreBackend.createFailureMacKey]). The primary copy is always written first. The threat model, and the defence against each
 * attack, is at the top of FailureDelay.kt.
 *
 * A save creates the key for its new sequence number, writes both copies atomically (temp, fsync, rename), and only then deletes
 * every other Delay State key. So a copy verifies only while its own key exists, and a pair older than the newest key the Keystore
 * lists is tampered. A deletion that the Keystore refuses does not fail the save, because the new pair is already on disk. The next
 * load or save deletes the keys that are left.
 *
 * The cap, the boot count and the time the wait had already run are in the MAC-protected payload. Each check made while a wait runs
 * saves the time served so far (see [checkDelay]), so a reboot continues the wait.
 *
 * Copies written before per-save keys (layout versions 1 and 2) verify under the one legacy key. [load] settles such a pair by
 * saving it again under a new sequence number, which deletes the legacy key. The pending delay is kept.
 *
 * No storage or Keystore error is thrown past [load], [checkDelay], [recordFailureOrLock] or [recordSuccess]. A lock or a reset that
 * this process could not save is held in memory (see [PENDING]), so the delay still applies to this process until a save goes through.
 * [recordFailure], [recordCap] and [initialize] still throw when the state cannot be saved: a cap change and a vault creation report
 * that to their caller.
 *
 * Assumptions and residual risk:
 * - A deleted Keystore key cannot be brought back. This class cannot prove it. It rests on KeyMint: the StrongBox guarantee the owner
 *   accepted, and whatever the TEE provides. The public SDK offers no rollback-resistance flag for app keys. A Delay State key that is
 *   not hardware-backed is refused in a release build instead (see [VaultKeystore.createFailureMacKey]).
 * - A Keystore that cannot list its keys adds nothing to the newest-key check. A pair restored while the deletion of its newer key
 *   also failed then verifies once, until the next load that can list the keys.
 * - A crash between making a key and writing the copies leaves a key newer than the pair on disk. The next load reads the pair as
 *   tampered and locks at the strictest cap, which fails closed.
 * - The directory is not fsynced after a rename. A power cut right after a save can leave the previous pair with its key already
 *   deleted, which reads as tampered: the owner's cap is kept when one copy verifies, and otherwise the strictest cap.
 * - A lock or reset that could not be saved lives in memory only. A new process reads the stored copies, which may be the stricter
 *   state, and makes a new lock from its first load if they cannot be read.
 */
internal class FailureStore(
    private val dir: File,
    private val keys: KeystoreBackend,
    private val readBootCount: () -> Int? = { null },
    private val write: (File, ByteArray) -> Unit = ::writeAtomically,
) {
    private val primary = File(dir, PRIMARY_FILE_NAME)
    private val backup = File(dir, BACKUP_FILE_NAME)
    private val seenKey = dir.absolutePath

    /** A copy that verified. [legacy] is true for a layout written before per-save keys. */
    private class Copy(val state: FailureState, val legacy: Boolean)

    /** Saves attempted by this instance, so [checkDelay] can tell whether its load already made this attempt's save. */
    private var saveAttempts = 0

    /** The device's current `Settings.Global.BOOT_COUNT`, or null when it could not be read. */
    fun currentBootCount(): Int? = readBootCount()

    /**
     * The stored state, or the lockout when the copies cannot be trusted. It never throws. A state this process could not save is
     * returned first, so no Keystore or storage error can lift it.
     */
    fun load(clock: Clock): FailureState {
        val written = WRITTEN[seenKey]
        val p = readCopy(primary)
        val b = readCopy(backup)
        // The newest Delay State key the Keystore holds, or the pair this process wrote last. A pair older than either was replaced by a
        // later save, even when a failed deletion left its key behind.
        val newest = maxOf(written?.state?.seq ?: 0L, keystoreOrNull { keys.failureMacKeySeqs() }?.maxOrNull() ?: 0L)
        val highestSeq = maxOf(p?.state?.seq ?: 0L, b?.state?.seq ?: 0L, newest)
        PENDING[seenKey]?.let { pending ->
            // A state this process could not save stays in force, with the times it was first made, until a save goes through.
            return trySave(pending.copy(seq = maxOf(highestSeq, pending.seq) + 1), clock) ?: pending
        }
        val bootCount = readBootCount()
        // A boot count that went down is tampering, whatever the copies say, because no reboot does that.
        val recorded = listOfNotNull(p, b).map { it.state } + listOfNotNull(written?.state)
        if (recorded.any { FailureDelay.bootCountWentBack(it, bootCount) }) {
            return lockAsTampered(clock, highestSeq, verified = emptyList())
        }
        if (p != null && b != null && p.state.seq >= b.state.seq && p.state.seq >= newest && !clockWentBack(p.state, clock, bootCount)) {
            // A legacy copy, or copies that are not one save apart, means a save or a migration was interrupted. Saving the state
            // again under a new sequence number settles both copies and deletes the keys they no longer use.
            if (p.legacy || b.legacy || p.state.seq != b.state.seq) return settle(p.state, clock)
            // Keys left behind by an interrupted save, or by a deletion the Keystore refused, are deleted here.
            deleteOtherKeys(p.state.seq)
            return p.state
        }
        // The lock this process wrote last is kept while the copies still hold exactly those bytes. Without this, a Keystore that cannot
        // read the key of that lock would lock again at every load, and the owner could never get through.
        if (written != null && written.state.failures >= TAMPER_FAILURES && onDisk(primary, written.bytes) && onDisk(backup, written.bytes)) {
            return written.state
        }
        // A copy verifies when its MAC checks out under its own key and no later save is known. Only copies that are not older than
        // the newest key count.
        val verified = listOfNotNull(p, b).map { it.state }.filter { it.seq >= newest }
        return lockAsTampered(clock, highestSeq, verified)
    }

    /** Saves a pair that [load] found legacy or out of step, under a new sequence number. A failed save leaves the pair as it verified. */
    private fun settle(state: FailureState, clock: Clock): FailureState =
        trySave(state.copy(seq = state.seq + 1), clock) ?: state

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

    /**
     * Records one more failed check, made at [clock]'s current time, and returns the new state.
     *
     * @throws VaultException or [IOException] when the state cannot be saved. [recordFailureOrLock] never throws.
     */
    fun recordFailure(previous: FailureState, clock: Clock): FailureState =
        save(
            previous.copy(
                seq = previous.seq + 1,
                failures = minOf(previous.failures + 1, MAX_FAILURES),
                lastFailureWallMs = clock.wallMillis(),
                lastFailureElapsedMs = clock.elapsedMillis(),
                // A new wait has served nothing, whatever the state it follows. The save takes this boot's count and reads 0.
                bootCount = null,
                elapsedAtSave = 0L,
            ),
            clock,
        )

    /**
     * [recordFailure], failing closed: when the failure cannot be saved, both copies are deleted, so a new process locks too instead
     * of granting a guess the count never saw, and this process locks at the strictest cap from now.
     */
    fun recordFailureOrLock(previous: FailureState, clock: Clock): FailureState = try {
        recordFailure(previous, clock)
    } catch (e: VaultException) {
        lockAfterUnsavedFailure(previous, clock)
    } catch (e: IOException) {
        lockAfterUnsavedFailure(previous, clock)
    }

    private fun lockAfterUnsavedFailure(previous: FailureState, clock: Clock): FailureState {
        primary.delete()
        backup.delete()
        // A new failure starts a new wait, so this lock is anchored now and replaces any earlier one.
        val locked = FailureState(
            seq = maxOf(previous.seq, WRITTEN[seenKey]?.state?.seq ?: 0L) + 1,
            failures = TAMPER_FAILURES,
            lastFailureWallMs = clock.wallMillis(),
            lastFailureElapsedMs = clock.elapsedMillis(),
            capMs = FailureDelay.MAX_CAP_MS,
        )
        PENDING[seenKey] = locked
        return locked
    }

    /**
     * Clears the failure count after a passing check. The state is written even when there was nothing to clear, so a passing check
     * costs the same two writes and fsyncs as a failed one and cannot be told apart by its storage work.
     *
     * A Keystore that will not make the new key may never refuse a correct password. The save is tried twice. When both fail, the reset
     * is kept in memory for this process, and nothing is written: a reset is never written under a key that is already on disk, because
     * two payloads would then verify under it, and an older one would bring the failures back. A new process sees the stored count, which
     * is the stricter one. This never throws.
     */
    fun recordSuccess(previous: FailureState, clock: Clock): FailureState {
        // The sequence number always moves on, so every save makes a key of its own and never replaces the key of the pair on disk.
        val reset = previous.copy(seq = previous.seq + 1, failures = 0)
        trySave(reset, clock)?.let { return it }
        trySave(reset, clock)?.let { return it }
        val cleared = previous.copy(failures = 0)
        PENDING[seenKey] = cleared
        return cleared
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

    /** What [checkDelay] found: the state as it was loaded, and whether the Failure Delay blocks the attempt. */
    class DelayCheck(val state: FailureState, val blocked: Boolean)

    /**
     * The Failure Delay check made before each password attempt, and the only such check. [vaultCreatedWallMs] is when the vault
     * was made, which sets the learning-period cap. While the wait runs, the attempt is blocked, and the time the wait has served
     * is saved, so that a reboot continues it (see the threat model in FailureDelay.kt).
     */
    fun checkDelay(clock: Clock, vaultCreatedWallMs: Long): DelayCheck {
        val savesBefore = saveAttempts
        val state = load(clock)
        val blocked = FailureDelay(vaultCreatedWallMs, FailureDelay.configuredCapFor(state)).isBlocked(state, clock, currentBootCount())
        if (!blocked) return DelayCheck(state, blocked = false)
        // A load that saved, a lockout for instance, has already done this attempt's one save.
        if (saveAttempts != savesBefore) return DelayCheck(state, blocked = true)
        return DelayCheck(recordWaitServed(state, clock), blocked = true)
    }

    /**
     * Saves the time the running wait has served, as of [clock] now. It is saved whether or not the device reports a boot count, so a
     * blocked attempt costs the same storage work as a failed or a passing one. A Keystore or storage error leaves the state as it was
     * loaded, which still blocks the attempt, so the check never fails because of this save.
     */
    private fun recordWaitServed(state: FailureState, clock: Clock): FailureState =
        trySave(state.copy(seq = state.seq + 1), clock) ?: state

    /** A wall clock moved back past the last failure within one boot. */
    private fun clockWentBack(state: FailureState, clock: Clock, bootCount: Int?): Boolean =
        state.failures > 0 && FailureDelay.elapsedSinceFailure(state, clock, bootCount) < 0

    /** True when [file] holds exactly [bytes]. A file that cannot be read does not. */
    private fun onDisk(file: File, bytes: ByteArray): Boolean =
        try {
            file.readBytes().contentEquals(bytes)
        } catch (e: IOException) {
            false
        }

    /**
     * The lockout for a state that cannot be verified. It is saved, which repairs a damaged copy. When it cannot be saved it is held
     * in memory, anchored at this first attempt, and [load] returns it until a save goes through. Later attempts do not move the end
     * of the wait.
     */
    private fun lockAsTampered(clock: Clock, highestSeq: Long, verified: List<FailureState>): FailureState {
        val locked = FailureState(
            seq = highestSeq + 1,
            failures = TAMPER_FAILURES,
            lastFailureWallMs = clock.wallMillis(),
            lastFailureElapsedMs = clock.elapsedMillis(),
            capMs = tamperedCap(verified),
        )
        return trySave(locked, clock) ?: locked.also { PENDING[seenKey] = it }
    }

    /** [save], or null when the Keystore or the storage refused it. */
    private fun trySave(state: FailureState, clock: Clock): FailureState? = try {
        save(state, clock)
    } catch (e: VaultException) {
        null
    } catch (e: IOException) {
        null
    }

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
     * Writes [state] under a new key for its sequence number, then deletes every other Delay State key. The keys of earlier states go
     * only after both copies carry the new one, so a crash leaves at least one key for each copy. The boot count and the time the wait
     * had already run are recorded with the state, so a reboot continues the wait instead of restarting it.
     */
    private fun save(state: FailureState, clock: Clock): FailureState {
        saveAttempts++
        dir.mkdirs()
        // Keys left by a deletion that failed are deleted again before this save makes its key, so nothing depends on remembering the
        // failure. The key kept is the one of the pair this process wrote last, which is the pair on disk. The number before this one is
        // not assumed, because a lockout can jump the sequence.
        WRITTEN[seenKey]?.let { deleteOtherKeys(it.state.seq) }
        val stamped = stamped(state, clock)
        val key = try {
            keystore(STATE_UNAVAILABLE) { keys.createFailureMacKey(stamped.seq) }
        } catch (e: VaultException) {
            discardKey(stamped.seq)
            throw e
        }
        val bytes = try {
            encode(stamped, key).also { write(primary, it) }
        } catch (e: VaultException) {
            discardKey(stamped.seq)
            throw e
        } catch (e: IOException) {
            discardKey(stamped.seq)
            throw e
        }
        // From here the primary names the new key, so the key stays even when the backup cannot be written: the next load settles the pair.
        write(backup, bytes)
        WRITTEN[seenKey] = Written(stamped, bytes)
        PENDING.remove(seenKey)
        // Once both copies carry the new state, every other key goes. The sequence numbers need not be consecutive.
        deleteOtherKeys(stamped.seq)
        return stamped
    }

    /** Deletes the key of [seq] after a save failed, so that no key newer than the pair on disk is left for a load to find. */
    private fun discardKey(seq: Long) {
        keystoreOrNull { keys.deleteFailureMacKey(seq) }
    }

    /**
     * Deletes every Delay State key except [keepSeq]. The pair on disk names [keepSeq], so the keys that go are not in use. A Keystore
     * error does not fail the caller, because the new pair is already on disk. The keys that are left are deleted by the next save or
     * load. Until then a newer key makes [load] read the pair as tampered, which fails closed.
     */
    private fun deleteOtherKeys(keepSeq: Long?) {
        keystoreOrNull { keys.deleteFailureMacKeysExcept(keepSeq) }
    }

    /**
     * [state] with the readings that are only known now: the device's boot count, and how much of the wait had run when the state
     * is written. The failure readings move onto this boot's clocks, so a later check in this boot measures from the same point,
     * and after a reboot the wait continues from the served time. The served time is never below zero, because a wall clock that
     * went back must not shorten the wait.
     */
    private fun stamped(state: FailureState, clock: Clock): FailureState {
        val bootCount = readBootCount()
        val served = if (state.failures == 0) 0L else maxOf(0L, FailureDelay.elapsedSinceFailure(state, clock, bootCount))
        return state.copy(
            bootCount = bootCount,
            elapsedAtSave = served,
            lastFailureWallMs = clock.wallMillis() - served,
            lastFailureElapsedMs = clock.elapsedMillis() - served,
        )
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
        // A Keystore error reading the key, or making the MAC, leaves the copy unverified. It does not fail the load.
        val key = keystoreOrNull {
            // Version 3 names its key by sequence number, which is read before the MAC is checked. A wrong number names no key,
            // or another one, so the MAC then fails.
            if (version == VERSION) keys.failureMacKey(ByteBuffer.wrap(payload).getLong(MAGIC.size + 1)) else keys.legacyFailureMacKey()
        } ?: return null
        val expected = keystoreOrNull { macOf(key, payload) } ?: return null
        if (!MessageDigest.isEqual(mac, expected)) return null
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

        /** The record this process last wrote to both copies, and the state it holds. Keyed by vault directory. */
        private val WRITTEN = ConcurrentHashMap<String, Written>()

        /**
         * A state this process could not save: a lock, or a reset. It keeps the times it was first made, and it stays in force until a
         * save goes through. Keyed by vault directory.
         */
        private val PENDING = ConcurrentHashMap<String, FailureState>()

        /** Forgets the in-process state for [dir]. Call this when the vault is wiped. A test calls it to model a restart. */
        fun forget(dir: File) {
            WRITTEN.remove(dir.absolutePath)
            PENDING.remove(dir.absolutePath)
        }
    }
}

/** The record this process last wrote: the state, and the bytes written to both copies. */
private class Written(val state: FailureState, val bytes: ByteArray)

/**
 * Runs a Keystore call. The Keystore's own errors become a [VaultException] with [message], so the app treats them as damage
 * and never as a crash.
 */
private inline fun <T> keystore(message: String, block: () -> T): T = try {
    block()
} catch (e: GeneralSecurityException) {
    throw VaultException(message, e)
} catch (e: ProviderException) {
    throw VaultException(message, e)
} catch (e: IOException) {
    throw VaultException(message, e)
}

/** Runs a Keystore call on a path that must not fail: a Keystore error gives null. */
private inline fun <T> keystoreOrNull(block: () -> T): T? = try {
    block()
} catch (e: GeneralSecurityException) {
    null
} catch (e: ProviderException) {
    null
} catch (e: IOException) {
    null
} catch (e: VaultException) {
    null
}
