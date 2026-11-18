package app.lensly

import android.content.Intent
import app.lensly.core.vault.deviceIsEmulator

/**
 * The debugHidden extra of the debug build, honoured on an emulator only, so adb can open the hidden area there. A benchmark
 * build is not debuggable, and on a phone the hidden area opens only by the two-finger gesture, as in release.
 */
internal object DebugLaunch {
    private const val EXTRA_HIDDEN = "debugHidden"

    /** True when [intent] asks for the hidden area on an emulator. Always false on a phone. */
    fun opensHidden(intent: Intent): Boolean = deviceIsEmulator() && intent.getBooleanExtra(EXTRA_HIDDEN, false)
}
