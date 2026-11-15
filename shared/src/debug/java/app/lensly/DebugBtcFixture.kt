package app.lensly

import android.content.Intent
import app.lensly.shared.BuildConfig

/**
 * Debug-only Bitcoin fixture for on-device demos. `adb shell am start -n app.lensly/.MainActivity --es debugPsbt <ur>` gives the app one
 * `ur:crypto-psbt`. The next sign screen of a wallet reads it as its first scan, so the real review, the real signature and the real
 * Review Record run on it. Lumio's next signature scan reads it the same way. Nothing else changes. The UR may be any type, not only a PSBT.
 *
 * This file lives in the debug source set only. The release and benchmark source sets have stubs that take nothing, so release builds
 * never contain the extra's name.
 */
object DebugBtcFixture {
    private const val EXTRA_PSBT = "debugPsbt"

    @Volatile private var pending: String? = null

    /** Keeps the fixture [intent] carries, if it carries one. Always a no-op unless this is a debug build. */
    fun offer(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        intent.getStringExtra(EXTRA_PSBT)?.let { pending = it }
    }

    /** The fixture waiting for a sign screen, or null. It is handed out once. */
    fun take(): String? = pending.also { pending = null }
}
