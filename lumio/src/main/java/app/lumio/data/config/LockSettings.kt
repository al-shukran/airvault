package app.lumio.data.config

import android.content.Context
import android.content.SharedPreferences
import app.lensly.ui.navigation.LockPolicy

/**
 * The lock choices of Settings → Security. Lumio locks after [idleMinutes] without a touch, and [lockWhenSwitchingApps] says whether
 * a switch to another app locks it too. The choices are kept outside the vault, as the camera's gesture is: the cover reads them
 * before anyone unlocks it, and they reveal no wallet.
 */
data class LockSettings(
    val idleMinutes: Int = DEFAULT_IDLE_MINUTES,
    val lockWhenSwitchingApps: Boolean = false,
) {
    /**
     * The cover's policy for these choices. The launches that Lumio makes itself, such as the document picker and the scanners, never
     * lock: they keep the vault open, and their return counts as a touch.
     */
    val policy: LockPolicy
        get() = LockPolicy(
            idleTimeoutMs = idleMinutes * MILLIS_PER_MINUTE,
            lockWhenBackgrounded = lockWhenSwitchingApps,
            ownLaunchesKeepUnlocked = true,
        )

    companion object {
        /** The default: thirty minutes without a touch. */
        const val DEFAULT_IDLE_MINUTES = 30

        /** The choices of the idle lock, in minutes. */
        val IDLE_CHOICES: List<Int> = listOf(1, 2, 5, 15, 30, 60)

        private const val MILLIS_PER_MINUTE = 60_000L
    }
}

/**
 * Reads and writes [LockSettings] in a plain preference file named for the lock. A missing or damaged value reads as its default.
 */
class LockSettingsStore(private val prefs: SharedPreferences) {

    /** The saved choices, or the defaults for any value that is missing or does not fit. */
    fun load(): LockSettings {
        val minutes = try {
            prefs.getInt(KEY_IDLE_MINUTES, LockSettings.DEFAULT_IDLE_MINUTES)
        } catch (e: ClassCastException) {
            LockSettings.DEFAULT_IDLE_MINUTES
        }
        val switching = try {
            prefs.getBoolean(KEY_SWITCHING, false)
        } catch (e: ClassCastException) {
            false
        }
        return LockSettings(
            idleMinutes = minutes.takeIf { it in LockSettings.IDLE_CHOICES } ?: LockSettings.DEFAULT_IDLE_MINUTES,
            lockWhenSwitchingApps = switching,
        )
    }

    /** Saves [settings]. The write runs in the background, and the screen shows the new choice at once. */
    fun save(settings: LockSettings) {
        prefs.edit()
            .putInt(KEY_IDLE_MINUTES, settings.idleMinutes)
            .putBoolean(KEY_SWITCHING, settings.lockWhenSwitchingApps)
            .apply()
    }

    companion object {
        /** The preference file. Its name says lock, not the vault. */
        const val FILE = "lumio_lock"

        /** The key holding the idle minutes. */
        const val KEY_IDLE_MINUTES = "idle_minutes"

        /** The key holding the switch that locks when the app is switched away from. */
        const val KEY_SWITCHING = "lock_when_switching"

        fun open(context: Context): LockSettingsStore =
            LockSettingsStore(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
