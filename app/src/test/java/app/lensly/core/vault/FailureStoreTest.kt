package app.lensly.core.vault

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FailureStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = MemoryKeystore()

    private fun open(dir: File, bootCount: () -> Int? = { null }) = FailureStore(dir, keys, bootCount)

    private fun File.primary() = File(this, FailureStore.PRIMARY_FILE_NAME)

    private fun File.backup() = File(this, FailureStore.BACKUP_FILE_NAME)

    /** Where the cap starts in a version 2 or 3 record: magic (4) + version (1) + seq (8) + failures (4) + wall (8) + elapsed (8). */
    private val capOffset = 33

    /** The MAC-covered part of a version 3 record: everything before the 32-byte MAC. */
    private val payloadBytes = 53

    /** The magic that opens every record. */
    private val magic = "LNFS".toByteArray(Charsets.US_ASCII)

    private fun macOf(key: SecretKey, payload: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(key)
        doFinal(payload)
    }

    /** A version 3 record for [state], MAC'd under [key]: the layout the store writes. */
    private fun encodeV3(state: FailureState, key: SecretKey): ByteArray {
        val payload = ByteBuffer.allocate(payloadBytes)
            .put(magic)
            .put(3)
            .putLong(state.seq)
            .putInt(state.failures)
            .putLong(state.lastFailureWallMs)
            .putLong(state.lastFailureElapsedMs)
            .putLong(state.capMs ?: 0L)
            .putInt(state.bootCount ?: -1)
            .putLong(state.elapsedAtSave)
            .array()
        return payload + macOf(key, payload)
    }

    /** A version 1 or 2 record for [state], MAC'd under the legacy key: the layout written before per-save keys. */
    private fun encodeLegacy(version: Int, state: FailureState, key: SecretKey): ByteArray {
        val buffer = ByteBuffer.allocate(if (version == 1) 33 else 41)
            .put(magic)
            .put(version.toByte())
            .putLong(state.seq)
            .putInt(state.failures)
            .putLong(state.lastFailureWallMs)
            .putLong(state.lastFailureElapsedMs)
        if (version == 2) buffer.putLong(state.capMs ?: 0L)
        val payload = buffer.array()
        return payload + macOf(key, payload)
    }

    /** Wraps a keystore so that its key deletion fails, which is what a power cut between the write and the delete looks like. */
    private class CrashBeforeDelete(private val inner: KeystoreBackend) : KeystoreBackend by inner {
        var armed = false

        override fun deleteFailureMacKeysExcept(keepSeq: Long?) {
            if (armed) throw IllegalStateException("power cut before the old key was deleted")
            inner.deleteFailureMacKeysExcept(keepSeq)
        }
    }

    @Test
    fun initialStateLoadsFromAFreshInstance() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        open(dir).initialize(clock)
        assertEquals(0, open(dir).load(clock).failures)
    }

    @Test
    fun failuresAndSuccessesArePersisted() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)

        val afterOne = store.recordFailure(store.load(clock), clock)
        store.recordFailure(afterOne, clock)
        assertEquals(2, open(dir).load(clock).failures)

        store.recordSuccess(open(dir).load(clock), clock)
        assertEquals(0, open(dir).load(clock).failures)
    }

    @Test
    fun recordSuccessWithoutFailuresChangesNothing() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        val state = store.load(clock)
        assertEquals(state, store.recordSuccess(state, clock))
    }

    @Test
    fun editedPrimaryCopyLocksForTheFullCap() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordFailure(store.load(clock), clock)

        val bytes = dir.primary().readBytes()
        bytes[10] = (bytes[10].toInt() xor 0x01).toByte()
        dir.primary().writeBytes(bytes)

        val delay = FailureDelay(FakeClock.START_WALL)
        val locked = open(dir).load(clock)
        assertEquals(FailureStore.TAMPER_FAILURES, locked.failures)
        assertTrue(delay.isBlocked(locked, clock))
        clock.advance(FailureDelay.LEARNING_CAP_MS - 1)
        assertTrue(delay.isBlocked(locked, clock))
        clock.advance(1)
        assertFalse(delay.isBlocked(locked, clock))
    }

    @Test
    fun missingBackupCopyIsTampering() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        open(dir).initialize(clock)
        dir.backup().delete()
        assertEquals(FailureStore.TAMPER_FAILURES, open(dir).load(clock).failures)
    }

    @Test
    fun rollingBackOnlyThePrimaryCopyIsTampering() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        val older = dir.primary().readBytes()
        store.recordFailure(store.load(clock), clock)

        dir.primary().writeBytes(older)
        assertEquals(FailureStore.TAMPER_FAILURES, open(dir).load(clock).failures)
    }

    @Test
    fun rollingBackBothCopiesIsDetectedWhileTheProcessIsAlive() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        val olderPrimary = dir.primary().readBytes()
        val olderBackup = dir.backup().readBytes()
        store.recordFailure(store.load(clock), clock)

        dir.primary().writeBytes(olderPrimary)
        dir.backup().writeBytes(olderBackup)
        assertEquals(FailureStore.TAMPER_FAILURES, open(dir).load(clock).failures)
    }

    @Test
    fun rollingBackBothCopiesIsDetectedAfterARestart() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        val olderPrimary = dir.primary().readBytes()
        val olderBackup = dir.backup().readBytes()
        store.recordFailure(store.load(clock), clock)

        dir.primary().writeBytes(olderPrimary)
        dir.backup().writeBytes(olderBackup)
        // The process restarts, so nothing is remembered in memory. The older pair names a key that was deleted at the next save.
        FailureStore.forget(dir)
        val locked = open(dir).load(clock)
        assertEquals(FailureStore.TAMPER_FAILURES, locked.failures)
        assertEquals(FailureDelay.MAX_CAP_MS, locked.capMs)
    }

    @Test
    fun wallClockMovedBackwardIsTampering() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordFailure(store.load(clock), clock)

        clock.wall -= 5
        assertEquals(FailureStore.TAMPER_FAILURES, open(dir).load(clock).failures)
    }

    @Test
    fun aRebootWithoutABootCountCannotTellTheWaitFromAMovedWallClock() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordFailure(store.load(clock), clock)

        // The device reboots: the monotonic clock restarts near zero, and the user moves the wall clock forward an hour.
        FailureStore.forget(dir)
        clock.elapsed = 2_000L
        clock.wall += FailureDelay.MAX_CAP_MS
        val reloaded = open(dir).load(clock)
        // This device reports no boot count, so the wall clock carries the wait, as on master: the hour ends it. The recorded
        // boot count is what closes this gap, see aRebootSeenByTheBootCountContinuesTheWait.
        assertEquals(1, reloaded.failures)
        assertFalse(FailureDelay(FakeClock.START_WALL).isBlocked(reloaded, clock))
    }

    @Test
    fun theBootCountIsRecordedWithTheStateAndKeptAcrossARead() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir) { 7 }
        store.initialize(clock)

        assertEquals(7, store.load(clock).bootCount)
        assertEquals(7, open(dir) { 7 }.load(clock).bootCount)
    }

    @Test
    fun aDeviceThatDoesNotReportTheBootCountRecordsNone() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)

        assertNull(store.load(clock).bootCount)
        assertNull(open(dir).load(clock).bootCount)
    }

    @Test
    fun aRebootSeenByTheBootCountContinuesTheWait() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        var bootCount = 7
        val store = open(dir) { bootCount }
        store.initialize(clock)
        clock.advance(FailureDelay.LEARNING_PERIOD_MS)
        var state = store.load(clock)
        repeat(4) { state = store.recordFailure(state, clock) }

        // Ten seconds of the 60 s wait run before the device reboots, and the next save records that much.
        clock.advance(10_000)
        state = store.recordCap(state, FailureDelay.DEFAULT_CAP_MS, clock)
        assertEquals(10_000L, state.elapsedAtSave)

        // The device reboots one boot later, five seconds into the new boot, with the wall clock moved forward an hour.
        FailureStore.forget(dir)
        bootCount = 8
        clock.elapsed = 5_000L
        clock.wall += FailureDelay.MAX_CAP_MS
        val reloaded = open(dir) { bootCount }.load(clock)
        val delay = FailureDelay(FakeClock.START_WALL, FailureDelay.configuredCapFor(reloaded))

        // Ten seconds before the reboot and five after it: 45 s of the 60 s wait are left.
        assertEquals(4, reloaded.failures)
        assertEquals(15_000L, FailureDelay.elapsedSinceFailure(reloaded, clock, bootCount))
        clock.advance(44_999)
        assertTrue(delay.isBlocked(reloaded, clock, bootCount))
        clock.advance(1)
        assertFalse(delay.isBlocked(reloaded, clock, bootCount))
    }

    @Test
    fun aRebootSeenByTheBootCountIsNotTamperingWhenTheWallClockMovedBack() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        var bootCount = 7
        clock.elapsed = 5_000L
        val store = open(dir) { bootCount }
        store.initialize(clock)
        store.recordFailure(store.load(clock), clock)

        // The device reboots, stays up for longer than that failure was in the previous boot, and the wall clock goes back an
        // hour. The monotonic clock alone would read as a wall clock moved backwards; the boot count shows a reboot.
        FailureStore.forget(dir)
        bootCount = 8
        clock.elapsed = 6_000L
        clock.wall -= FailureDelay.MAX_CAP_MS
        val reloaded = open(dir) { bootCount }.load(clock)

        assertEquals(1, reloaded.failures)
        assertTrue(FailureDelay(FakeClock.START_WALL).isBlocked(reloaded, clock, bootCount))
    }

    @Test
    fun freshVaultUsesTheDefaultCap() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        open(dir).initialize(clock)
        val loaded = open(dir).load(clock)
        assertNull(loaded.capMs)
        assertEquals(FailureDelay.DEFAULT_CAP_MS, FailureDelay.configuredCapFor(loaded))
    }

    @Test
    fun capRoundTripsThroughTheFile() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)

        store.recordCap(store.load(clock), 5_000L, clock)
        assertEquals(5_000L, open(dir).load(clock).capMs)

        store.recordCap(open(dir).load(clock), FailureDelay.MAX_CAP_MS, clock)
        assertEquals(FailureDelay.MAX_CAP_MS, open(dir).load(clock).capMs)

        store.recordCap(open(dir).load(clock), FailureDelay.MIN_CAP_MS, clock)
        assertEquals(FailureDelay.MIN_CAP_MS, open(dir).load(clock).capMs)

        store.recordCap(open(dir).load(clock), null, clock)
        assertNull(open(dir).load(clock).capMs)
    }

    @Test
    fun capSurvivesFailuresAndSuccesses() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordCap(store.load(clock), 300_000L, clock)

        store.recordFailure(store.load(clock), clock)
        assertEquals(300_000L, open(dir).load(clock).capMs)

        store.recordSuccess(open(dir).load(clock), clock)
        val reloaded = open(dir).load(clock)
        assertEquals(0, reloaded.failures)
        assertEquals(300_000L, reloaded.capMs)
    }

    @Test
    fun capOutsideOneSecondToOneHourIsRefusedAndNotSaved() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        val state = store.load(clock)

        assertFailsWith<IllegalArgumentException> { store.recordCap(state, 999L, clock) }
        assertFailsWith<IllegalArgumentException> { store.recordCap(state, FailureDelay.MAX_CAP_MS + 1, clock) }
        assertNull(open(dir).load(clock).capMs)
    }

    @Test
    fun editedCapInThePrimaryCopyKeepsTheOwnersCapAndRepairsThePrimary() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordCap(store.load(clock), 5_000L, clock)

        // Change the owner's 5 s cap in the primary copy, and leave its MAC as it was.
        val bytes = dir.primary().readBytes()
        bytes[capOffset + 7] = (bytes[capOffset + 7].toInt() xor 0x01).toByte()
        dir.primary().writeBytes(bytes)

        // The backup still verifies, so its cap is kept, and saving the lockout rewrites the primary.
        val locked = open(dir).load(clock)
        assertEquals(FailureStore.TAMPER_FAILURES, locked.failures)
        assertEquals(5_000L, locked.capMs)
        val repaired = open(dir).load(clock)
        assertEquals(FailureStore.TAMPER_FAILURES, repaired.failures)
        assertEquals(5_000L, repaired.capMs)
    }

    @Test
    fun editedCapInBothCopiesLocksToTheStrictestCap() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordCap(store.load(clock), 5_000L, clock)

        for (file in listOf(dir.primary(), dir.backup())) {
            val bytes = file.readBytes()
            bytes[capOffset + 7] = (bytes[capOffset + 7].toInt() xor 0x01).toByte()
            file.writeBytes(bytes)
        }

        val locked = open(dir).load(clock)
        assertEquals(FailureStore.TAMPER_FAILURES, locked.failures)
        assertEquals(FailureDelay.MAX_CAP_MS, locked.capMs)
    }

    @Test
    fun missingBackupKeepsTheOwnersCap() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordCap(store.load(clock), 5_000L, clock)
        dir.backup().delete()

        val locked = open(dir).load(clock)
        assertEquals(FailureStore.TAMPER_FAILURES, locked.failures)
        assertEquals(5_000L, locked.capMs)
    }

    @Test
    fun missingBothCopiesLocksToTheStrictestCap() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordCap(store.load(clock), 5_000L, clock)
        dir.primary().delete()
        dir.backup().delete()

        val locked = open(dir).load(clock)
        assertEquals(FailureStore.TAMPER_FAILURES, locked.failures)
        assertEquals(FailureDelay.MAX_CAP_MS, locked.capMs)
    }

    @Test
    fun rolledBackPrimaryWhileAliveKeepsTheCapThisProcessSaved() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordCap(store.load(clock), FailureDelay.MAX_CAP_MS, clock)
        val olderOneHour = dir.primary().readBytes()
        store.recordCap(store.load(clock), 5_000L, clock)

        // The primary is put back to the one-hour copy. Its key was deleted at the 5 s save, so it does not verify.
        dir.primary().writeBytes(olderOneHour)

        val locked = open(dir).load(clock)
        assertEquals(FailureStore.TAMPER_FAILURES, locked.failures)
        assertEquals(5_000L, locked.capMs)
    }

    @Test
    fun rolledBackPrimaryAfterARestartKeepsTheStricterCap() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordCap(store.load(clock), 5_000L, clock)
        val olderFiveSeconds = dir.primary().readBytes()
        store.recordCap(store.load(clock), FailureDelay.MAX_CAP_MS, clock)

        // The primary is put back to the 5 s copy. After a restart this process has seen nothing, and its key is gone.
        dir.primary().writeBytes(olderFiveSeconds)
        FailureStore.forget(dir)

        val locked = open(dir).load(clock)
        assertEquals(FailureStore.TAMPER_FAILURES, locked.failures)
        assertEquals(FailureDelay.MAX_CAP_MS, locked.capMs)
    }

    @Test
    fun rollbackToAnOlderPairIsDetectedAfterARestartAndLocksToTheStrictestCap() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        val olderPrimary = dir.primary().readBytes()
        val olderBackup = dir.backup().readBytes()
        store.recordFailure(store.load(clock), clock)

        // The attacker snapshotted the pair before the failure, and puts it back after the restart.
        dir.primary().writeBytes(olderPrimary)
        dir.backup().writeBytes(olderBackup)
        FailureStore.forget(dir)

        val locked = open(dir).load(clock)
        assertEquals(FailureStore.TAMPER_FAILURES, locked.failures)
        assertEquals(FailureDelay.MAX_CAP_MS, locked.capMs)
        // The lockout is saved, so the keys of the pairs the attacker used are gone and one key remains.
        assertEquals(1, keys.failureMacKeyCount())
    }

    @Test
    fun onlyOneDelayStateKeyExistsAfterEachSave() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        assertEquals(1, keys.failureMacKeyCount())

        var state = store.load(clock)
        repeat(5) {
            state = store.recordFailure(state, clock)
            assertEquals(1, keys.failureMacKeyCount())
        }
        state = store.recordCap(state, 5_000L, clock)
        assertEquals(1, keys.failureMacKeyCount())
        store.recordSuccess(state, clock)
        assertEquals(1, keys.failureMacKeyCount())
    }

    @Test
    fun crashBetweenTheWriteAndTheDeleteRecoversToTheNewState() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        open(dir).initialize(clock)
        val crashing = CrashBeforeDelete(keys)
        val store = FailureStore(dir, crashing)
        val previous = store.load(clock)

        crashing.armed = true
        assertFailsWith<IllegalStateException> { store.recordFailure(previous, clock) }
        // Both copies now name the new state, and the key of the state before it still exists.
        assertEquals(2, keys.failureMacKeyCount())

        FailureStore.forget(dir)
        val recovered = open(dir).load(clock)
        assertEquals(1, recovered.failures)
        assertEquals(2L, recovered.seq)
        assertEquals(1, keys.failureMacKeyCount())
    }

    @Test
    fun crashBetweenTheTwoCopiesSettlesOnTheNextLoad() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        val previous = store.load(clock)

        // A directory where the backup's temp file must go makes the second write fail, after the primary has the new state.
        File(dir, "failures.bak.tmp").mkdirs()
        assertFailsWith<IOException> { store.recordFailure(previous, clock) }
        File(dir, "failures.bak.tmp").deleteRecursively()

        val settled = open(dir).load(clock)
        assertEquals(1, settled.failures)
        assertEquals(3L, settled.seq)
        assertEquals(1, keys.failureMacKeyCount())
        assertEquals(settled, open(dir).load(clock))
    }

    @Test
    fun residualOneStepRollbackWhileThePreviousKeyStillExists() {
        // This documents the residual risk named in FailureStore: a power cut between the write and the delete, then a restore of
        // the copies from before the save. The previous key still exists, so that state verifies once, until the next load.
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        val beforeTheSavePrimary = dir.primary().readBytes()
        val beforeTheSaveBackup = dir.backup().readBytes()
        val crashing = CrashBeforeDelete(keys)
        val crashingStore = FailureStore(dir, crashing)
        val previous = crashingStore.load(clock)
        crashing.armed = true
        assertFailsWith<IllegalStateException> { crashingStore.recordFailure(previous, clock) }

        dir.primary().writeBytes(beforeTheSavePrimary)
        dir.backup().writeBytes(beforeTheSaveBackup)
        FailureStore.forget(dir)

        val recovered = open(dir).load(clock)
        assertEquals(0, recovered.failures)
        assertEquals(1L, recovered.seq)
        // The next load deleted the key of the state that was rolled back to, and the newer key too.
        assertEquals(1, keys.failureMacKeyCount())
    }

    @Test
    fun migrationFromTheSingleKeyLayoutKeepsAPendingDelay() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val legacyKey = keys.createLegacyFailureMacKey()
        val legacyState = FailureState(
            seq = 7,
            failures = 3,
            lastFailureWallMs = clock.wall,
            lastFailureElapsedMs = clock.elapsed,
            capMs = 5_000L,
        )
        val legacy = encodeLegacy(version = 2, legacyState, legacyKey)
        dir.mkdirs()
        dir.primary().writeBytes(legacy)
        dir.backup().writeBytes(legacy)

        val migrated = open(dir).load(clock)
        assertEquals(3, migrated.failures)
        assertEquals(5_000L, migrated.capMs)
        assertEquals(8L, migrated.seq)
        // The legacy key is deleted, and the one key that remains is the new one.
        assertNull(keys.legacyFailureMacKey())
        assertEquals(1, keys.failureMacKeyCount())
        // The pending wait is what it was before the migration: 5 s, the owner's cap, measured from the failure.
        val delay = FailureDelay(FakeClock.START_WALL, FailureDelay.configuredCapFor(migrated))
        assertTrue(delay.isBlocked(migrated, clock))
        clock.advance(5_000)
        assertFalse(delay.isBlocked(migrated, clock))
        // Loading again gives the same state, with nothing more to settle.
        assertEquals(migrated, open(dir).load(clock))
    }

    @Test
    fun aMigrationInterruptedBetweenTheCopiesIsSettled() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val legacyKey = keys.createLegacyFailureMacKey()
        val legacyState = FailureState(seq = 7, failures = 3, lastFailureWallMs = clock.wall, lastFailureElapsedMs = clock.elapsed, capMs = 5_000L)
        val legacy = encodeLegacy(version = 2, legacyState, legacyKey)
        dir.mkdirs()
        dir.primary().writeBytes(legacy)
        dir.backup().writeBytes(legacy)

        // The primary already holds the new layout, the backup still the legacy one, and the legacy key is alive.
        val newKey = keys.createFailureMacKey(8L)
        dir.primary().writeBytes(encodeV3(legacyState.copy(seq = 8L), newKey))

        val settled = open(dir).load(clock)
        assertEquals(3, settled.failures)
        assertEquals(5_000L, settled.capMs)
        assertEquals(9L, settled.seq)
        assertNull(keys.legacyFailureMacKey())
        assertEquals(1, keys.failureMacKeyCount())
    }

    @Test
    fun aVersionOneRecordFromBeforeTheCapLoadsAsDefaultWithItsCount() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val legacyKey = keys.createLegacyFailureMacKey()
        val legacyState = FailureState(seq = 2, failures = 1, lastFailureWallMs = clock.wall, lastFailureElapsedMs = clock.elapsed)
        val record = encodeLegacy(version = 1, legacyState, legacyKey)
        dir.mkdirs()
        dir.primary().writeBytes(record)
        dir.backup().writeBytes(record)

        val loaded = open(dir).load(clock)
        assertEquals(1, loaded.failures)
        assertNull(loaded.capMs)
        assertNull(keys.legacyFailureMacKey())
    }

    @Test
    fun aKeystoreThatRefusesAKeyWritesNothingAndReportsUnavailable() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        val refusing = object : KeystoreBackend by keys {
            override fun createFailureMacKey(seq: Long): SecretKey = throw GeneralSecurityException("refused")
        }
        val previous = open(dir).load(clock)

        assertFailsWith<VaultException> { FailureStore(dir, refusing).recordFailure(previous, clock) }
        assertEquals(0, open(dir).load(clock).failures)
    }

    @Test
    fun validMacOverAnOutOfRangeCapIsTampering() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        open(dir).initialize(clock)

        // A record this app would never write: the MAC is valid, but 1 ms is below the 1 s minimum.
        val payload = dir.primary().readBytes().copyOfRange(0, payloadBytes)
        ByteBuffer.wrap(payload).putLong(capOffset, 1L)
        val record = payload + macOf(keys.failureMacKey(1L)!!, payload)
        dir.primary().writeBytes(record)
        dir.backup().writeBytes(record)

        // Neither copy verifies, so the cap is the strictest one allowed.
        val locked = open(dir).load(clock)
        assertEquals(FailureStore.TAMPER_FAILURES, locked.failures)
        assertEquals(FailureDelay.MAX_CAP_MS, locked.capMs)
    }
}
