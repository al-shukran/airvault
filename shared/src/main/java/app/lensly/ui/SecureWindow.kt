package app.lensly.ui

import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import app.lensly.shared.BuildConfig

/**
 * Blocks screenshots, screen recordings and the recents thumbnail for this activity.
 *
 * Every build sets FLAG_SECURE, except a debug build running on an emulator, so development
 * screenshots still work there. Real devices are always secure, debug or release.
 */
fun ComponentActivity.applySecureWindow() {
    if (!isSecureRequired(BuildConfig.DEBUG, Build.HARDWARE, Build.PRODUCT, Build.FINGERPRINT)) return
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    if (Build.VERSION.SDK_INT >= 33) {
        setRecentsScreenshotEnabled(false)
    }
}

/** Pure decision, kept free of android.os.Build so it can run on the JVM. */
internal fun isSecureRequired(debug: Boolean, hardware: String, product: String, fingerprint: String): Boolean =
    !(debug && isEmulator(hardware, product, fingerprint))

internal fun isEmulator(hardware: String, product: String, fingerprint: String): Boolean =
    hardware == "ranchu" ||
        hardware == "goldfish" ||
        product.startsWith("sdk_") ||
        product.contains("emulator") ||
        fingerprint.startsWith("generic")
