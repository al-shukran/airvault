package app.lumio.data.config

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The Security choices: thirty minutes without a touch and the switch off by default, and the cover's policy each choice makes. */
@RunWith(RobolectricTestRunner::class)
class LockSettingsTest {

    private val minute = 60_000L

    private fun store(): LockSettingsStore = LockSettingsStore.open(RuntimeEnvironment.getApplication())

    @Test
    fun theDefaultsAreThirtyMinutesAndTheSwitchOff() {
        val defaults = LockSettings()
        assertEquals(30, defaults.idleMinutes)
        assertFalse(defaults.lockWhenSwitchingApps)
    }

    @Test
    fun theDefaultPolicyLocksAfterThirtyMinutesAndNotOnAStop() {
        val policy = LockSettings().policy
        assertEquals(30 * minute, policy.idleTimeoutMs)
        assertFalse(policy.lockWhenBackgrounded, "switching apps must not lock with the switch off")
        assertTrue(policy.ownLaunchesKeepUnlocked, "Lumio's own launches never lock")
    }

    @Test
    fun theSwitchOnMakesAStopLock() {
        assertTrue(LockSettings(lockWhenSwitchingApps = true).policy.lockWhenBackgrounded)
    }

    @Test
    fun theChosenTimeoutIsTheIdleTimeoutOfThePolicy() {
        assertEquals(5 * minute, LockSettings(idleMinutes = 5).policy.idleTimeoutMs)
    }

    @Test
    fun aMissingFileReadsAsTheDefaults() {
        assertEquals(LockSettings(), store().load())
    }

    @Test
    fun savedChoicesComeBack() {
        val saved = LockSettings(idleMinutes = 15, lockWhenSwitchingApps = true)
        store().save(saved)
        assertEquals(saved, store().load())
    }

    @Test
    fun aTimeoutOutsideTheChoicesReadsAsTheDefault() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences(LockSettingsStore.FILE, 0)
        prefs.edit().putInt(LockSettingsStore.KEY_IDLE_MINUTES, 7).commit()
        assertEquals(LockSettings.DEFAULT_IDLE_MINUTES, store().load().idleMinutes)
    }

    @Test
    fun aDamagedValueReadsAsTheDefault() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences(LockSettingsStore.FILE, 0)
        prefs.edit()
            .putString(LockSettingsStore.KEY_IDLE_MINUTES, "thirty")
            .putString(LockSettingsStore.KEY_SWITCHING, "yes")
            .commit()
        assertEquals(LockSettings(), store().load())
    }
}
