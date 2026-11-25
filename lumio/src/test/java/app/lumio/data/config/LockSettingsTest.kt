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
    fun theChoicesRunFromOneMinuteUpToOneMonthInOrder() {
        val timed = AutoLock.entries.filter { !it.lockWhenLeaving }
        assertEquals(timed.sortedBy { it.idleMinutes }, timed)
        assertEquals(30 * 24 * 60 * minute, LockSettings(AutoLock.AFTER_ONE_MONTH).policy.idleTimeoutMs)
        assertEquals(AutoLock.AFTER_ONE_MONTH, AutoLock.entries.last())
    }

    @Test
    fun aTimedChoiceNeverLocksOnALeave() {
        AutoLock.entries.filter { it != AutoLock.IMMEDIATELY }.forEach {
            assertFalse(LockSettings(it).policy.lockWhenBackgrounded, it.name)
        }
    }

    @Test
    fun lumioOneValuesReadAsTheChoiceThatLocksNoLater() {
        mapOf(1 to AutoLock.AFTER_ONE_MINUTE, 2 to AutoLock.AFTER_ONE_MINUTE, 5 to AutoLock.AFTER_FIVE_MINUTES, 15 to AutoLock.AFTER_FIFTEEN_MINUTES,
            30 to AutoLock.AFTER_THIRTY_MINUTES, 60 to AutoLock.AFTER_ONE_HOUR).forEach { (minutes, expected) ->
            prefs().edit().clear().putInt("idle_minutes", minutes).putBoolean("lock_when_switching", false).commit()
            assertEquals(LockSettings(expected), store().load(), "$minutes minutes")
        }
    }

    @Test
    fun lumioOneLockWhenSwitchingReadsAsImmediately() {
        prefs().edit().putInt("idle_minutes", 30).putBoolean("lock_when_switching", true).commit()
        assertEquals(LockSettings(AutoLock.IMMEDIATELY), store().load())
    }

    @Test
    fun savingAChoiceDropsTheLumioOneValues() {
        prefs().edit().putInt("idle_minutes", 60).commit()
        store().save(LockSettings(AutoLock.AFTER_ONE_DAY))
        assertEquals(setOf(LockSettingsStore.KEY_AUTO_LOCK), prefs().all.keys)
        assertEquals(LockSettings(AutoLock.AFTER_ONE_DAY), store().load())
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
