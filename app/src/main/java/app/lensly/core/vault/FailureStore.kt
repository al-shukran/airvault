package app.lensly.core.vault

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Persists [FailureState] as two HMAC-SHA256 copies in the vault directory: `failures.bin`, then
 * `failures.bak`. The primary copy is always written first, so a crash between the two writes leaves
 * the primary as the newer copy.
 *
 * The Failure Delay cap is part of the MAC-protected payload, so it is read before any unlock and cannot be
 * changed in the file without detection. It lives here rather than in the encrypted vault settings, because
 * the delay applies before the vault can be unlocked.
 *
 * A copy counts as tampered when its file is missing, its MAC does not verify, its sequence number is lower
 * than the other copy's, or it is lower than the last sequence number this process has seen. A copy whose
 * last failure lies in the future (the wall clock moved backwards) also counts. Tampering replaces the state
 * with one that blocks for the full cap, and that state is saved. The cap of a tampered state is the spec
 * default, not the owner's choice, because the owner's choice can no longer be read.
 *
 * Rollback resistance is limited. If an attacker restores a matching older pair of copies after the process
 * has restarted, nothing here detects it. Closing that gap needs hardware-backed monotonic storage.
 */
internal class FailureStore(
    private val dir: File,
    private val macKey: SecretKey,
) {
    private val primary = File(dir, PRIMARY_FILE_NAME)
    private val backup = File(dir, BACKUP_FILE_NAME)
    private val seenKey = dir.absolutePath

    /** Loads the stored state, or returns the cap-locking state if the copies look tampered. */
    fun load(clock: Clock): FailureState {
        val p = readCopy(primary)
        val b = readCopy(backup)
        val seen = SEQ_SEEN[seenKey] ?: 0L
        if (p != null && b != null && p.seq >= b.seq && p.seq >= seen && !wallMovedBack(p, clock)) {
            return p
        }
        return lockAsTampered(clock, highestSeq = maxOf(p?.seq ?: 0L, b?.seq ?: 0L, seen))
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
        )
    }

    /** Records one more failed check, made at [clock]'s current time, and returns the new state. */
    fun recordFailure(previous: FailureState, clock: Clock): FailureState {
        val next = previous.copy(
            seq = previous.seq + 1,
            failures = minOf(previous.failures + 1, MAX_FAILURES),
            lastFailureWallMs = clock.wallMillis(),
            lastFailureElapsedMs = clock.elapsedMillis(),
        )
        save(next)
        return next
    }

    /** Resets the failure count after a passing check. Returns [previous] unchanged when there is nothing to reset. */
    fun recordSuccess(previous: FailureState): FailureState {
        if (previous.failures == 0) return previous
        val next = previous.copy(seq = previous.seq + 1, failures = 0)
        save(next)
        return next
    }

    /**
     * Saves the Failure Delay cap the owner chose, or null for Default, and returns the new state. The failure count
     * is left as it is. The caller has already checked the App Password.
     */
    fun recordCap(previous: FailureState, capMs: Long?): FailureState {
        if (capMs != null) {
            require(capMs in FailureDelay.MIN_CAP_MS..FailureDelay.MAX_CAP_MS) { "cap must be between 1 s and 1 h" }
        }
        val next = previous.copy(seq = previous.seq + 1, capMs = capMs)
        save(next)
        return next
    }

    private fun wallMovedBack(state: FailureState, clock: Clock): Boolean =
        state.failures > 0 && FailureDelay.elapsedSinceFailure(state, clock) < 0

    private fun lockAsTampered(clock: Clock, highestSeq: Long): FailureState {
        val locked = FailureState(
            seq = highestSeq + 1,
            failures = TAMPER_FAILURES,
            lastFailureWallMs = clock.wallMillis(),
            lastFailureElapsedMs = clock.elapsedMillis(),
        )
        save(locked)
        return locked
    }

    private fun save(state: FailureState) {
        dir.mkdirs()
        val bytes = encode(state)
        writeAtomically(primary, bytes)
        writeAtomically(backup, bytes)
        SEQ_SEEN[seenKey] = state.seq
    }

    private fun readCopy(file: File): FailureState? {
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            return null
        }
        // A version 1 record has no cap, so it is shorter. Its MAC still covers everything before the MAC.
        val payloadSize = bytes.size - MAC_SIZE
        if (payloadSize != PAYLOAD_SIZE && payloadSize != PAYLOAD_SIZE_V1) return null
        val payload = bytes.copyOfRange(0, payloadSize)
        if (!MessageDigest.isEqual(bytes.copyOfRange(payloadSize, bytes.size), macOf(payload))) return null
        val buffer = ByteBuffer.wrap(payload)
        val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
        if (!magic.contentEquals(MAGIC)) return null
        val version = buffer.get()
        val seq = buffer.getLong()
        val failures = buffer.getInt()
        val lastFailureWallMs = buffer.getLong()
        val lastFailureElapsedMs = buffer.getLong()
        return when (version) {
            // Written before the cap existed, so the owner had not chosen one. The next save writes version 2.
            VERSION_1 -> if (payloadSize == PAYLOAD_SIZE_V1) {
                FailureState(seq, failures, lastFailureWallMs, lastFailureElapsedMs, capMs = null)
            } else {
                null
            }
            VERSION -> {
                if (payloadSize != PAYLOAD_SIZE) return null
                val capMs = when (val stored = buffer.getLong()) {
                    NO_CAP_ON_DISK -> null
                    in FailureDelay.MIN_CAP_MS..FailureDelay.MAX_CAP_MS -> stored
                    // A MAC-valid cap outside the offered range is not something this app writes, so the copy is not trusted.
                    else -> return null
                }
                FailureState(seq, failures, lastFailureWallMs, lastFailureElapsedMs, capMs)
            }
            else -> null
        }
    }

    private fun encode(state: FailureState): ByteArray {
        val payload = ByteBuffer.allocate(PAYLOAD_SIZE)
            .put(MAGIC)
            .put(VERSION)
            .putLong(state.seq)
            .putInt(state.failures)
            .putLong(state.lastFailureWallMs)
            .putLong(state.lastFailureElapsedMs)
            .putLong(state.capMs ?: NO_CAP_ON_DISK)
            .array()
        return payload + macOf(payload)
    }

    private fun macOf(payload: ByteArray): ByteArray {
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(macKey)
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

        /** Version 2 added the cap. A version 1 record is still read, with the cap at Default. */
        private const val VERSION_1: Byte = 1
        private const val VERSION: Byte = 2

        /** The cap on disk when the owner chose Default. Zero is outside the offered range, so it cannot be a real cap. */
        private const val NO_CAP_ON_DISK = 0L

        /** magic (4) + version (1) + seq (8) + failures (4) + wall (8) + elapsed (8). */
        private const val PAYLOAD_SIZE_V1 = 4 + 1 + 8 + 4 + 8 + 8

        /** Version 1 payload plus the cap (8). */
        private const val PAYLOAD_SIZE = PAYLOAD_SIZE_V1 + 8
        private const val MAC_SIZE = 32

        /** Highest sequence number this process has written, keyed by vault directory. */
        private val SEQ_SEEN = ConcurrentHashMap<String, Long>()

        /** Forgets the in-process sequence number for [dir]. Call this when the vault is wiped. */
        fun forget(dir: File) {
            SEQ_SEEN.remove(dir.absolutePath)
        }
    }
}
