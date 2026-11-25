package app.lumio.data.config

import android.content.Context
import android.content.SharedPreferences
import app.lensly.ui.navigation.LockPolicy

/**
 * When Lumio locks itself, the choices of Settings → Security → Auto-Lock. Each choice locks after [idleMinutes] without a touch, and
 * [lockWhenLeaving] locks the moment Lumio leaves the foreground as well. The choices are kept outside the vault, as the camera's gesture is:
 * the cover reads them before anyone unlocks it, and they reveal no wallet.
 */
enum class AutoLock(val label: String, val idleMinutes: Int, val lockWhenLeaving: Boolean) {
    IMMEDIATELY("Immediately", idleMinutes = 1, lockWhenLeaving = true),
    AFTER_ONE_MINUTE("After 1 Minute", idleMinutes = 1, lockWhenLeaving = false),
    AFTER_FIVE_MINUTES("After 5 Minutes", idleMinutes = 5, lockWhenLeaving = false),
    AFTER_FIFTEEN_MINUTES("After 15 Minutes", idleMinutes = 15, lockWhenLeaving = false),
    AFTER_THIRTY_MINUTES("After 30 Minutes", idleMinutes = 30, lockWhenLeaving = false),
    AFTER_ONE_HOUR("After 1 Hour", idleMinutes = 60, lockWhenLeaving = false),
    AFTER_ONE_DAY("After 1 Day", idleMinutes = 24 * 60, lockWhenLeaving = false),
    AFTER_ONE_WEEK("After 1 Week", idleMinutes = 7 * 24 * 60, lockWhenLeaving = false),
    AFTER_ONE_MONTH("After 1 Month", idleMinutes = 30 * 24 * 60, lockWhenLeaving = false),
}

/** The lock choice of Settings → Security. It is [AutoLock.AFTER_ONE_MINUTE] until the owner picks another. */
data class LockSettings(val autoLock: AutoLock = AutoLock.AFTER_ONE_MINUTE) {
    /**
     * The cover's policy for this choice. The launches that Lumio makes itself, such as the document picker and the scanners, never
     * lock: they keep the vault open, and their return counts as a touch.
     */
    val policy: LockPolicy
        get() = LockPolicy(
            idleTimeoutMs = autoLock.idleMinutes * MILLIS_PER_MINUTE,
            lockWhenBackgrounded = autoLock.lockWhenLeaving,
            ownLaunchesKeepUnlocked = true,
        )

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}

/**
 * Reads and writes [LockSettings] in a plain preference file named for the lock. A missing or damaged value reads as its default.
 */
class LockSettingsStore(private val prefs: SharedPreferences) {

    /**
     * The saved choice, or the default when the value is missing or names no choice. A value that Lumio 1.0.0 saved, idle minutes and a switch
     * that locks on a leave, reads as the choice that locks no later than it did.
     */
    fun load(): LockSettings {
        val name = try {
            prefs.getString(KEY_AUTO_LOCK, null)
        } catch (e: ClassCastException) {
            null
        }
        val choice = AutoLock.entries.firstOrNull { it.name == name } ?: if (name == null) released() else null
        return LockSettings(autoLock = choice ?: AutoLock.AFTER_ONE_MINUTE)
    }

    /** The choice that Lumio 1.0.0's two values stand for, or null when they are missing or damaged. A minutes value between two choices rounds down. */
    private fun released(): AutoLock? = try {
        if (prefs.getBoolean(KEY_RELEASED_SWITCHING, false)) {
            AutoLock.IMMEDIATELY
        } else {
            val minutes = prefs.getInt(KEY_RELEASED_IDLE_MINUTES, 0)
            AutoLock.entries.lastOrNull { !it.lockWhenLeaving && it.idleMinutes <= minutes }
        }
    } catch (e: ClassCastException) {
        null
    }

    /** Saves [settings]. The write runs in the background, and the screen shows the new choice at once. */
    fun save(settings: LockSettings) {
        prefs.edit().putString(KEY_AUTO_LOCK, settings.autoLock.name).remove(KEY_RELEASED_IDLE_MINUTES).remove(KEY_RELEASED_SWITCHING).apply()
    }

    /** Removes every saved choice, so [load] reads the defaults. It waits for the write, because a reset must not leave a choice behind. */
    fun erase() {
        prefs.edit().clear().commit()
    }

    companion object {
        /** The preference file. Its name says lock, not the vault. */
        const val FILE = "lumio_lock"

        /** The key holding the name of the Auto-Lock choice. */
        const val KEY_AUTO_LOCK = "auto_lock"

        /** The keys of Lumio 1.0.0: the idle minutes, and the switch that locks when the app is switched away from. */
        private const val KEY_RELEASED_IDLE_MINUTES = "idle_minutes"
        private const val KEY_RELEASED_SWITCHING = "lock_when_switching"

        fun open(context: Context): LockSettingsStore =
            LockSettingsStore(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
