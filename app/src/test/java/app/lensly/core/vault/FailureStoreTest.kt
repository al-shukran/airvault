package app.lensly.core.vault

import java.io.File
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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

    private val macKey = SecretKeySpec(ByteArray(32) { (it * 7 + 1).toByte() }, "HmacSHA256")

    private fun open(dir: File) = FailureStore(dir, macKey)

    private fun File.primary() = File(this, FailureStore.PRIMARY_FILE_NAME)

    private fun File.backup() = File(this, FailureStore.BACKUP_FILE_NAME)

    /** Where the cap starts in a record: magic (4) + version (1) + seq (8) + failures (4) + wall (8) + elapsed (8). */
    private val capOffset = 33

    /** The MAC-covered part of a record: everything before the 32-byte MAC. */
    private val payloadBytes = 41

    private fun macOf(payload: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(macKey)
        doFinal(payload)
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

        store.recordSuccess(open(dir).load(clock))
        assertEquals(0, open(dir).load(clock).failures)
    }

    @Test
    fun recordSuccessWithoutFailuresChangesNothing() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        val state = store.load(clock)
        assertEquals(state, store.recordSuccess(state))
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

        store.recordCap(store.load(clock), 5_000L)
        assertEquals(5_000L, open(dir).load(clock).capMs)

        store.recordCap(open(dir).load(clock), FailureDelay.MAX_CAP_MS)
        assertEquals(FailureDelay.MAX_CAP_MS, open(dir).load(clock).capMs)

        store.recordCap(open(dir).load(clock), FailureDelay.MIN_CAP_MS)
        assertEquals(FailureDelay.MIN_CAP_MS, open(dir).load(clock).capMs)

        store.recordCap(open(dir).load(clock), null)
        assertNull(open(dir).load(clock).capMs)
    }

    @Test
    fun capSurvivesFailuresAndSuccesses() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordCap(store.load(clock), 300_000L)

        store.recordFailure(store.load(clock), clock)
        assertEquals(300_000L, open(dir).load(clock).capMs)

        store.recordSuccess(open(dir).load(clock))
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

        assertFailsWith<IllegalArgumentException> { store.recordCap(state, 999L) }
        assertFailsWith<IllegalArgumentException> { store.recordCap(state, FailureDelay.MAX_CAP_MS + 1) }
        assertNull(open(dir).load(clock).capMs)
    }

    @Test
    fun editedCapInThePrimaryCopyKeepsTheOwnersCapAndRepairsThePrimary() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordCap(store.load(clock), 5_000L)

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
        store.recordCap(store.load(clock), 5_000L)

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
        store.recordCap(store.load(clock), 5_000L)
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
        store.recordCap(store.load(clock), 5_000L)
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
        store.recordCap(store.load(clock), FailureDelay.MAX_CAP_MS)
        val olderOneHour = dir.primary().readBytes()
        store.recordCap(store.load(clock), 5_000L)

        // The primary is put back to the one-hour copy. This process has seen the 5 s save, so that copy is stale.
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
        store.recordCap(store.load(clock), 5_000L)
        val olderFiveSeconds = dir.primary().readBytes()
        store.recordCap(store.load(clock), FailureDelay.MAX_CAP_MS)

        // The primary is put back to the 5 s copy. After a restart this process has seen nothing, so both copies verify.
        dir.primary().writeBytes(olderFiveSeconds)
        FailureStore.forget(dir)

        val locked = open(dir).load(clock)
        assertEquals(FailureStore.TAMPER_FAILURES, locked.failures)
        assertEquals(FailureDelay.MAX_CAP_MS, locked.capMs)
    }

    @Test
    fun version1RecordFromBeforeTheCapLoadsAsDefaultWithItsCount() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordFailure(store.load(clock), clock)

        // Rewrite both copies in the version 1 layout: no cap, version byte 1, and a MAC over that shorter payload.
        val v2 = dir.primary().readBytes()
        val payload = v2.copyOfRange(0, payloadBytes - 8)
        payload[4] = 1
        val record = payload + macOf(payload)
        dir.primary().writeBytes(record)
        dir.backup().writeBytes(record)

        val loaded = open(dir).load(clock)
        assertEquals(1, loaded.failures)
        assertNull(loaded.capMs)
    }

    @Test
    fun validMacOverAnOutOfRangeCapIsTampering() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        open(dir).initialize(clock)

        // A record this app would never write: the MAC is valid, but 1 ms is below the 1 s minimum.
        val payload = dir.primary().readBytes().copyOfRange(0, payloadBytes)
        ByteBuffer.wrap(payload).putLong(capOffset, 1L)
        val record = payload + macOf(payload)
        dir.primary().writeBytes(record)
        dir.backup().writeBytes(record)

        // Neither copy verifies, so the cap is the strictest one allowed.
        val locked = open(dir).load(clock)
        assertEquals(FailureStore.TAMPER_FAILURES, locked.failures)
        assertEquals(FailureDelay.MAX_CAP_MS, locked.capMs)
    }
}
