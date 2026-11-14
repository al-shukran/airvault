package app.lensly

import android.content.Intent
import app.lensly.shared.BuildConfig

/**
 * Debug-only launch hook. adb cannot send the two-finger hidden gesture, so
 * `adb shell am start -n app.lensly/.MainActivity --ez debugHidden true` opens the hidden area directly, and the same
 * extra works on `app.lumio/.MainActivity`.
 *
 * This file lives in the debug source set only. The release and benchmark source sets have stubs that always return false,
 * so release builds never contain the extra's name.
 */
internal object DebugLaunch {
    private const val EXTRA_HIDDEN = "debugHidden"

    /** True when [intent] asks for the hidden area. Always false unless this is a debug build. */
    fun opensHidden(intent: Intent): Boolean =
        BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_HIDDEN, false)
}
