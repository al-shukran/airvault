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

    /** Where the cap starts in a record: magic (4) + version (1) + seq (8) + failures (4) + wall (8) + elapsed (8). */
    private val capOffset = 33

    /** The MAC-covered part of a record: everything before the 32-byte MAC. */
    private val payloadBytes = 70

    /** The magic that opens every record. */
    private val magic = "LNFS".toByteArray(Charsets.US_ASCII)

    private fun macOf(key: SecretKey, payload: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(key)
        doFinal(payload)
    }

    /** Wraps a keystore so that its key deletion fails, which is what a Keystore error after the write looks like. */
    private class DeleteFails(private val inner: KeystoreBackend) : KeystoreBackend by inner {
        var armed = false

        override fun deleteFailureMacKeysExcept(keepSeq: Long?) {
            if (armed) throw GeneralSecurityException("the old key could not be deleted")
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
    fun recordSuccessWithoutFailuresKeepsTheCountAtZero() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        val after = store.recordSuccess(store.load(clock), clock)
        assertEquals(0, after.failures)
        assertEquals(0, open(dir).load(clock).failures)
    }

    @Test
    fun editedPrimaryCopyKeepsTheCountAndRestartsTheWaitAtTheFullCap() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordFailure(store.load(clock), clock)

        val bytes = dir.primary().readBytes()
        bytes[10] = (bytes[10].toInt() xor 0x01).toByte()
        dir.primary().writeBytes(bytes)

        // The backup still verifies, so the one failure is kept, and the wait restarts at the owner's full cap of one minute.
        val delay = FailureDelay(FakeClock.START_WALL)
        val locked = open(dir).load(clock)
        assertEquals(1, locked.failures)
        assertTrue(delay.isBlocked(locked, clock))
        clock.advance(FailureDelay.DEFAULT_CAP_MS - 1)
        assertTrue(delay.isBlocked(locked, clock))
        clock.advance(1)
        assertFalse(delay.isBlocked(locked, clock))
    }

    @Test
    fun missingBackupCopyKeepsTheFailureCount() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordFailure(store.load(clock), clock)
        dir.backup().delete()
        assertEquals(1, open(dir).load(clock).failures)
    }

    @Test
    fun rollingBackOnlyThePrimaryCopyKeepsTheFailureCount() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        val older = dir.primary().readBytes()
        store.recordFailure(store.load(clock), clock)

        dir.primary().writeBytes(older)
        assertEquals(1, open(dir).load(clock).failures)
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
    fun wallClockMovedBackwardKeepsTheFailureCountAndRestartsTheWaitAtTheFullCap() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordFailure(store.load(clock), clock)

        clock.wall -= 5
        val locked = open(dir).load(clock)
        assertEquals(1, locked.failures)
        val delay = FailureDelay(FakeClock.START_WALL)
        assertTrue(delay.isBlocked(locked, clock))
        clock.advance(FailureDelay.DEFAULT_CAP_MS - 1)
        assertTrue(delay.isBlocked(locked, clock))
        clock.advance(1)
        assertFalse(delay.isBlocked(locked, clock))
    }

    @Test
    fun aRebootWithoutABootCountIsNotShortenedByAForwardWallClockJump() {
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
        // This device reports no boot count. A reboot credits no more than the time since boot, so the hour cannot end the 10 s wait.
        assertEquals(1, reloaded.failures)
        assertTrue(FailureDelay(FakeClock.START_WALL).isBlocked(reloaded, clock))
    }

    @Test
    fun aBootCountRaisedWithoutARebootCannotEndTheWait() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        var bootCount = 7
        val store = open(dir) { bootCount }
        store.initialize(clock)
        store.recordFailure(store.load(clock), clock)

        // The attacker writes BOOT_COUNT up by one, with no reboot. Four seconds in, the attempt is checked and blocked.
        bootCount = 8
        clock.advance(4_000)
        assertTrue(open(dir) { bootCount }.checkDelay(clock, FakeClock.START_WALL).blocked)
        // The wait is 10 s. Master read the raised count as a reboot, and this check let the attempt through.
        clock.advance(5_999)
        assertTrue(open(dir) { bootCount }.checkDelay(clock, FakeClock.START_WALL).blocked)
        clock.advance(1)
        assertFalse(open(dir) { bootCount }.checkDelay(clock, FakeClock.START_WALL).blocked)
    }

    @Test
    fun aDecreasedBootCountKeepsTheCountAndCapAndRestartsTheWaitAtTheCap() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir) { 8 }
        store.initialize(clock)
        store.recordLimits(store.load(clock), 10_000L, FailureDelay.DEFAULT_WIPE_AFTER, clock)
        store.recordFailure(store.load(clock), clock)

        // No reboot lowers the count. The copies still verify, so the one failure and the owner's 10 s cap are kept, and the wait
        // restarts at that cap.
        val locked = open(dir) { 7 }.load(clock)
        assertEquals(1, locked.failures)
        assertEquals(10_000L, locked.capMs)
        val delay = FailureDelay(FakeClock.START_WALL, 10_000L)
        assertTrue(delay.isBlocked(locked, clock, bootCount = 7))
        clock.advance(9_999)
        assertTrue(delay.isBlocked(locked, clock, bootCount = 7))
        clock.advance(1)
        assertFalse(delay.isBlocked(locked, clock, bootCount = 7))
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

        // Ten seconds into the 60 s wait, the next check is made the way an unlock makes it. That check saves the time served.
        clock.advance(10_000)
        val check = store.checkDelay(clock, FakeClock.START_WALL)
        assertTrue(check.blocked)
        assertEquals(10_000L, open(dir) { bootCount }.load(clock).elapsedAtSave)

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

        // The device reboots, and the wall clock goes back an hour. The monotonic clock restarts below the failure's reading, so the
        // reboot is seen, and the recorded boot count continues the wait instead of reading the backwards clock as tampering.
        FailureStore.forget(dir)
        bootCount = 8
        clock.elapsed = 1_000L
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

        store.recordLimits(store.load(clock), 10_000L, FailureDelay.DEFAULT_WIPE_AFTER, clock)
        assertEquals(10_000L, open(dir).load(clock).capMs)

        store.recordLimits(open(dir).load(clock), FailureDelay.MAX_CAP_MS, FailureDelay.DEFAULT_WIPE_AFTER, clock)
        assertEquals(FailureDelay.MAX_CAP_MS, open(dir).load(clock).capMs)

        store.recordLimits(open(dir).load(clock), FailureDelay.MIN_CAP_MS, FailureDelay.DEFAULT_WIPE_AFTER, clock)
        assertEquals(FailureDelay.MIN_CAP_MS, open(dir).load(clock).capMs)

        store.recordLimits(open(dir).load(clock), null, FailureDelay.DEFAULT_WIPE_AFTER, clock)
        assertNull(open(dir).load(clock).capMs)
    }

    @Test
    fun capSurvivesFailuresAndSuccesses() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordLimits(store.load(clock), 300_000L, FailureDelay.DEFAULT_WIPE_AFTER, clock)

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

        assertFailsWith<IllegalArgumentException> { store.recordLimits(state, 999L, FailureDelay.DEFAULT_WIPE_AFTER, clock) }
        assertFailsWith<IllegalArgumentException> { store.recordLimits(state, FailureDelay.MAX_CAP_MS + 1, FailureDelay.DEFAULT_WIPE_AFTER, clock) }
        assertNull(open(dir).load(clock).capMs)
    }

    @Test
    fun editedCapInThePrimaryCopyKeepsTheOwnersCapAndRepairsThePrimary() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordLimits(store.load(clock), 10_000L, FailureDelay.DEFAULT_WIPE_AFTER, clock)

        // Change the owner's 5 s cap in the primary copy, and leave its MAC as it was.
        val bytes = dir.primary().readBytes()
        bytes[capOffset + 7] = (bytes[capOffset + 7].toInt() xor 0x01).toByte()
        dir.primary().writeBytes(bytes)

        // The backup still verifies, so its cap is kept, and saving the lockout rewrites the primary.
        val locked = open(dir).load(clock)
        assertEquals(0, locked.failures)
        assertEquals(10_000L, locked.capMs)
        // Even with no wrong try, the tampered lockout holds the owner's cap.
        assertTrue(FailureDelay(FakeClock.START_WALL, 10_000L).isBlocked(locked, clock))
        val repaired = open(dir).load(clock)
        assertEquals(0, repaired.failures)
        assertEquals(10_000L, repaired.capMs)
    }

    @Test
    fun editedCapInBothCopiesLocksToTheStrictestCap() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordLimits(store.load(clock), 10_000L, FailureDelay.DEFAULT_WIPE_AFTER, clock)

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
        store.recordLimits(store.load(clock), 10_000L, FailureDelay.DEFAULT_WIPE_AFTER, clock)
        dir.backup().delete()

        val locked = open(dir).load(clock)
        assertEquals(0, locked.failures)
        assertEquals(10_000L, locked.capMs)
    }

    @Test
    fun missingBothCopiesLocksToTheStrictestCap() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordLimits(store.load(clock), 10_000L, FailureDelay.DEFAULT_WIPE_AFTER, clock)
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
        store.recordLimits(store.load(clock), FailureDelay.MAX_CAP_MS, FailureDelay.DEFAULT_WIPE_AFTER, clock)
        val olderOneHour = dir.primary().readBytes()
        store.recordLimits(store.load(clock), 10_000L, FailureDelay.DEFAULT_WIPE_AFTER, clock)

        // The primary is put back to the one-hour copy. Its key was deleted at the 5 s save, so it does not verify.
        dir.primary().writeBytes(olderOneHour)

        val locked = open(dir).load(clock)
        assertEquals(0, locked.failures)
        assertEquals(10_000L, locked.capMs)
    }

    @Test
    fun rolledBackPrimaryAfterARestartKeepsTheStricterCap() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordLimits(store.load(clock), 10_000L, FailureDelay.DEFAULT_WIPE_AFTER, clock)
        val olderFiveSeconds = dir.primary().readBytes()
        store.recordLimits(store.load(clock), FailureDelay.MAX_CAP_MS, FailureDelay.DEFAULT_WIPE_AFTER, clock)

        // The primary is put back to the 5 s copy. After a restart this process has seen nothing, and its key is gone.
        dir.primary().writeBytes(olderFiveSeconds)
        FailureStore.forget(dir)

        val locked = open(dir).load(clock)
        assertEquals(0, locked.failures)
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
        state = store.recordLimits(state, 10_000L, FailureDelay.DEFAULT_WIPE_AFTER, clock)
        assertEquals(1, keys.failureMacKeyCount())
        store.recordSuccess(state, clock)
        assertEquals(1, keys.failureMacKeyCount())
    }

    @Test
    fun aFailedKeyDeleteDoesNotFailTheSaveAndTheNextSaveRetriesIt() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        open(dir).initialize(clock)
        val deleting = DeleteFails(keys)
        val store = FailureStore(dir, deleting)
        val previous = store.load(clock)

        // The delete fails after both copies carry the new state. The save stands, and the old key is still there.
        deleting.armed = true
        val saved = store.recordFailure(previous, clock)
        assertEquals(2L, saved.seq)
        assertEquals(2, keys.failureMacKeyCount())

        // A new store, as each unlock builds one, tries the deletion again on its next save even though it cannot make its key.
        deleting.armed = false
        val refusing = object : KeystoreBackend by deleting {
            override fun createFailureMacKey(seq: Long): SecretKey = throw GeneralSecurityException("refused")
        }
        assertFailsWith<VaultException> { FailureStore(dir, refusing).recordFailure(saved, clock) }
        assertEquals(1, keys.failureMacKeyCount())

        // With a working Keystore the save goes through and one key is left.
        val next = open(dir).recordFailure(saved, clock)
        assertEquals(3L, next.seq)
        assertEquals(2, next.failures)
        assertEquals(1, keys.failureMacKeyCount())
    }

    @Test
    fun crashBetweenTheTwoCopiesSettlesOnTheNextLoad() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        val previous = store.load(clock)

        // A non-empty directory at the backup's path makes the second write fail at the rename, after the primary has the
        // new state. The old backup is put back afterwards, as it is on disk when the crash comes before the second write.
        val backup = File(dir, "failures.bak")
        val oldBackup = backup.readBytes()
        backup.delete()
        backup.mkdirs()
        File(backup, "held").writeText("x")
        assertFailsWith<IOException> { store.recordFailure(previous, clock) }
        backup.deleteRecursively()
        backup.writeBytes(oldBackup)

        val settled = open(dir).load(clock)
        assertEquals(1, settled.failures)
        assertEquals(3L, settled.seq)
        assertEquals(1, keys.failureMacKeyCount())
        assertEquals(settled, open(dir).load(clock))
    }

    @Test
    fun aRollbackWhileAFailedDeletionLeftTheNewerKeyIsTampered() {
        // A deletion that fails after a save leaves the older key behind. A restore of the copies from before the save names that
        // key, but the Keystore still lists the newer one, so the older pair is tampered: it locks at the strictest cap.
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        val beforeTheSavePrimary = dir.primary().readBytes()
        val beforeTheSaveBackup = dir.backup().readBytes()
        val deleting = DeleteFails(keys)
        val crashingStore = FailureStore(dir, deleting)
        deleting.armed = true
        crashingStore.recordFailure(crashingStore.load(clock), clock)

        dir.primary().writeBytes(beforeTheSavePrimary)
        dir.backup().writeBytes(beforeTheSaveBackup)
        FailureStore.forget(dir)

        val locked = open(dir).load(clock)
        assertEquals(FailureStore.TAMPER_FAILURES, locked.failures)
        assertEquals(FailureDelay.MAX_CAP_MS, locked.capMs)
        // The lockout is saved under a new key, and the older keys are gone.
        assertEquals(1, keys.failureMacKeyCount())
    }

    @Test
    fun aCrashBetweenTheWriteAndTheKeyDeleteIsRecoveredOnTheNextLoad() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        open(dir).initialize(clock)
        val deleting = DeleteFails(keys)
        val store = FailureStore(dir, deleting)
        // The save writes both copies, the deletion of the old key fails, and then the process dies.
        deleting.armed = true
        store.recordFailure(store.load(clock), clock)
        assertEquals(2, keys.failureMacKeyCount())
        FailureStore.forget(dir)

        // The next load finds the pair under its newest key, and deletes the key it no longer needs. Nothing was held in memory.
        deleting.armed = false
        val recovered = open(dir).load(clock)
        assertEquals(1, recovered.failures)
        assertEquals(2L, recovered.seq)
        assertEquals(1, keys.failureMacKeyCount())
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

    @Test
    fun aSeqJumpFromTheTamperPathLeavesOneKeyAfterTheNextSave() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        open(dir).initialize(clock)
        val firstPrimary = dir.primary().readBytes()
        val firstBackup = dir.backup().readBytes()
        var state = open(dir).load(clock)
        repeat(3) { state = open(dir).recordFailure(state, clock) }

        // The copies are rolled back to the first pair, whose key is gone. This process has written sequence 4, so the lockout
        // jumps to 5 and does not follow the pair on disk. Its key deletion fails, which leaves one key behind for the next save.
        dir.primary().writeBytes(firstPrimary)
        dir.backup().writeBytes(firstBackup)
        val deleting = DeleteFails(keys)
        deleting.armed = true
        val locked = FailureStore(dir, deleting).load(clock)
        assertEquals(5L, locked.seq)
        assertEquals(FailureStore.TAMPER_FAILURES, locked.failures)
        assertEquals(2, keys.failureMacKeyCount())

        // The next save writes sequence 6, and the only key left is the one for that sequence number.
        deleting.armed = false
        val next = FailureStore(dir, deleting).recordFailure(locked, clock)
        assertEquals(6L, next.seq)
        assertEquals(1, keys.failureMacKeyCount())
        assertEquals(next, open(dir).load(clock))
    }

    /** One day in milliseconds. */
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun aClockSetBackIntoTheLearningWeekKeepsTheOwnersCap() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir) { 7 }
        store.initialize(clock)
        store.recordLimits(store.load(clock), FailureDelay.MAX_CAP_MS, FailureDelay.DEFAULT_WIPE_AFTER, clock)

        // A month passes, then the clock is set back to the vault's first day.
        clock.advance(30 * day)
        clock.wall = FakeClock.START_WALL + day
        var state = store.load(clock)
        repeat(4) { state = store.recordFailure(state, clock) }

        // Four wrong tries under the owner's one-hour cap wait 80 s. The learning cap would wait 10 s.
        clock.advance(79_999)
        assertTrue(store.checkDelay(clock, FakeClock.START_WALL).blocked)
        clock.advance(1)
        assertFalse(store.checkDelay(clock, FakeClock.START_WALL).blocked)
    }

    @Test
    fun aClockSetBeforeTheVaultWasMadeIsPastTheLearningWeek() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir) { 7 }
        store.initialize(clock)
        store.recordLimits(store.load(clock), FailureDelay.MAX_CAP_MS, FailureDelay.DEFAULT_WIPE_AFTER, clock)

        clock.wall = FakeClock.START_WALL - day
        var state = store.load(clock)
        repeat(4) { state = store.recordFailure(state, clock) }

        clock.advance(79_999)
        assertTrue(store.checkDelay(clock, FakeClock.START_WALL).blocked)
        clock.advance(1)
        assertFalse(store.checkDelay(clock, FakeClock.START_WALL).blocked)
    }

    @Test
    fun aNewVaultKeepsTheLearningCap() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir) { 7 }
        store.initialize(clock)
        store.recordLimits(store.load(clock), FailureDelay.MAX_CAP_MS, FailureDelay.DEFAULT_WIPE_AFTER, clock)
        var state = store.load(clock)
        repeat(4) { state = store.recordFailure(state, clock) }

        clock.advance(FailureDelay.LEARNING_CAP_MS)
        assertFalse(store.checkDelay(clock, FakeClock.START_WALL).blocked)
    }

    @Test
    fun aRebootSeenOnlyByTheBootCountEndsTheLearningWeek() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        var bootCount = 7
        val store = open(dir) { bootCount }
        store.initialize(clock)
        store.recordLimits(store.load(clock), FailureDelay.MAX_CAP_MS, FailureDelay.DEFAULT_WIPE_AFTER, clock)

        // The device reboots. Its clock reads past the reading at creation, so only BOOT_COUNT shows the reboot.
        FailureStore.forget(dir)
        bootCount = 8
        clock.elapsed = FakeClock.START_ELAPSED + 60_000L
        var state = open(dir) { bootCount }.load(clock)
        repeat(4) { state = open(dir) { bootCount }.recordFailure(state, clock) }

        clock.advance(79_999)
        assertTrue(open(dir) { bootCount }.checkDelay(clock, FakeClock.START_WALL).blocked)
        clock.advance(1)
        assertFalse(open(dir) { bootCount }.checkDelay(clock, FakeClock.START_WALL).blocked)
    }

    @Test
    fun aForwardClockJumpDoesNotShortenAPendingWait() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir) { 7 }
        store.initialize(clock)
        clock.advance(30 * day)
        store.recordLimits(store.load(clock), FailureDelay.MAX_CAP_MS, FailureDelay.DEFAULT_WIPE_AFTER, clock)
        var state = store.load(clock)
        repeat(4) { state = store.recordFailure(state, clock) }

        // Twenty seconds into the 80 s wait, the wall clock jumps an hour forward. The monotonic clock still sets the wait.
        clock.advance(20_000)
        clock.wall += FailureDelay.MAX_CAP_MS
        assertTrue(store.checkDelay(clock, FakeClock.START_WALL).blocked)
        clock.advance(59_999)
        assertTrue(store.checkDelay(clock, FakeClock.START_WALL).blocked)
        clock.advance(1)
        assertFalse(store.checkDelay(clock, FakeClock.START_WALL).blocked)
    }

    @Test
    fun aLockoutWithNoWrongTryEndsAfterItsCapEvenWhenTheOwnerRetries() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordLimits(store.load(clock), 10_000L, FailureDelay.DEFAULT_WIPE_AFTER, clock)

        // Tamper with the owner's cap in the primary copy. The backup verifies, and the lockout keeps no wrong try.
        val bytes = dir.primary().readBytes()
        bytes[capOffset + 7] = (bytes[capOffset + 7].toInt() xor 0x01).toByte()
        dir.primary().writeBytes(bytes)

        assertTrue(open(dir).checkDelay(clock, FakeClock.START_WALL).blocked)
        // A retry during the wait saves the time served, so the wait still ends 10 s after the lockout, not 10 s after the retry.
        clock.advance(6_000)
        assertTrue(open(dir).checkDelay(clock, FakeClock.START_WALL).blocked)
        clock.advance(4_000)
        assertFalse(open(dir).checkDelay(clock, FakeClock.START_WALL).blocked)
    }

    @Test
    fun aWriteThatFailsDuringACheckStillBlocksTheAttempt() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        open(dir).initialize(clock)
        val failed = open(dir).recordFailure(open(dir).load(clock), clock)
        clock.advance(4_000)

        // The boot count is reported, so the check saves the time served, and the storage refuses the write.
        val refusing = FailureStore(dir, keys, { 7 }, write = { _, _ -> throw IOException("no space left on the device") })
        val check = refusing.checkDelay(clock, FakeClock.START_WALL)
        assertTrue(check.blocked)

        // Nothing was written, so the copies still hold the state from before the check.
        assertEquals(failed, open(dir).load(clock))
    }
}
