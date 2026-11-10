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
 * A copy counts as tampered when its file is missing, its MAC does not verify, its sequence number is lower
 * than the other copy's, or it is lower than the last sequence number this process has seen. A copy whose
 * last failure lies in the future (the wall clock moved backwards) also counts. Tampering replaces the state
 * with one that blocks for the full cap, and that state is saved.
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
        val next = FailureState(
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
        if (bytes.size != RECORD_SIZE) return null
        val payload = bytes.copyOfRange(0, PAYLOAD_SIZE)
        if (!MessageDigest.isEqual(bytes.copyOfRange(PAYLOAD_SIZE, RECORD_SIZE), macOf(payload))) return null
        val buffer = ByteBuffer.wrap(payload)
        val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
        if (!magic.contentEquals(MAGIC) || buffer.get() != VERSION) return null
        return FailureState(
            seq = buffer.getLong(),
            failures = buffer.getInt(),
            lastFailureWallMs = buffer.getLong(),
            lastFailureElapsedMs = buffer.getLong(),
        )
    }

    private fun encode(state: FailureState): ByteArray {
        val payload = ByteBuffer.allocate(PAYLOAD_SIZE)
            .put(MAGIC)
            .put(VERSION)
            .putLong(state.seq)
            .putInt(state.failures)
            .putLong(state.lastFailureWallMs)
            .putLong(state.lastFailureElapsedMs)
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
        private const val VERSION: Byte = 1

        /** magic (4) + version (1) + seq (8) + failures (4) + wall (8) + elapsed (8). */
        private const val PAYLOAD_SIZE = 4 + 1 + 8 + 4 + 8 + 8
        private const val MAC_SIZE = 32
        private const val RECORD_SIZE = PAYLOAD_SIZE + MAC_SIZE

        /** Highest sequence number this process has written, keyed by vault directory. */
        private val SEQ_SEEN = ConcurrentHashMap<String, Long>()

        /** Forgets the in-process sequence number for [dir]. Call this when the vault is wiped. */
        fun forget(dir: File) {
            SEQ_SEEN.remove(dir.absolutePath)
        }
    }
}
