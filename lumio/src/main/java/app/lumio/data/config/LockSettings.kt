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

    /** The saved choice, or the default when the value is missing or names no choice. */
    fun load(): LockSettings {
        val name = try {
            prefs.getString(KEY_AUTO_LOCK, null)
        } catch (e: ClassCastException) {
            null
        }
        return LockSettings(autoLock = AutoLock.entries.firstOrNull { it.name == name } ?: AutoLock.AFTER_ONE_MINUTE)
    }

    /** Saves [settings]. The write runs in the background, and the screen shows the new choice at once. */
    fun save(settings: LockSettings) {
        prefs.edit().putString(KEY_AUTO_LOCK, settings.autoLock.name).apply()
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

        fun open(context: Context): LockSettingsStore =
            LockSettingsStore(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
