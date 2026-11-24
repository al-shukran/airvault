package app.lumio.data.config

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The Auto-Lock choices: after one minute without a touch by default, and the cover's policy each choice makes. */
@RunWith(RobolectricTestRunner::class)
class LockSettingsTest {

    private val minute = 60_000L

    private fun store(): LockSettingsStore = LockSettingsStore.open(RuntimeEnvironment.getApplication())

    private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences(LockSettingsStore.FILE, 0)

    @Test
    fun theDefaultIsAfterOneMinute() {
        assertEquals(AutoLock.AFTER_ONE_MINUTE, LockSettings().autoLock)
    }

    @Test
    fun theDefaultPolicyLocksAfterOneMinuteAndNotOnALeave() {
        val policy = LockSettings().policy
        assertEquals(1 * minute, policy.idleTimeoutMs)
        assertFalse(policy.lockWhenBackgrounded, "leaving Lumio must not lock under After 1 Minute")
        assertTrue(policy.ownLaunchesKeepUnlocked, "Lumio's own launches never lock")
    }

    @Test
    fun immediatelyLocksWhenLumioLeavesTheForeground() {
        val policy = LockSettings(AutoLock.IMMEDIATELY).policy
        assertTrue(policy.lockWhenBackgrounded)
        assertTrue(policy.ownLaunchesKeepUnlocked, "Lumio's own launches never lock, even under Immediately")
    }

    @Test
    fun immediatelyKeepsTheOneMinuteIdleLockBehindTheLeaveLock() {
        assertEquals(1 * minute, LockSettings(AutoLock.IMMEDIATELY).policy.idleTimeoutMs)
    }

    @Test
    fun afterFiveMinutesIsTheIdleTimeoutAndDoesNotLockOnALeave() {
        val policy = LockSettings(AutoLock.AFTER_FIVE_MINUTES).policy
        assertEquals(5 * minute, policy.idleTimeoutMs)
        assertFalse(policy.lockWhenBackgrounded)
    }

    @Test
    fun aMissingFileReadsAsTheDefault() {
        assertEquals(LockSettings(), store().load())
    }

    @Test
    fun everyChoiceComesBackAfterSaving() {
        AutoLock.entries.forEach { choice ->
            store().save(LockSettings(choice))
            assertEquals(LockSettings(choice), store().load())
        }
    }

    @Test
    fun aValueThatNamesNoChoiceReadsAsTheDefault() {
        prefs().edit().putString(LockSettingsStore.KEY_AUTO_LOCK, "AFTER_TEN_MINUTES").commit()
        assertEquals(LockSettings(), store().load())
    }

    @Test
    fun aDamagedValueReadsAsTheDefault() {
        prefs().edit().putInt(LockSettingsStore.KEY_AUTO_LOCK, 5).commit()
        assertEquals(LockSettings(), store().load())
    }

    @Test
    fun eraseRemovesTheChoiceSoItReadsAsTheDefault() {
        store().save(LockSettings(AutoLock.IMMEDIATELY))
        store().erase()
        assertTrue(prefs().all.isEmpty())
        assertEquals(LockSettings(), store().load())
    }
}
