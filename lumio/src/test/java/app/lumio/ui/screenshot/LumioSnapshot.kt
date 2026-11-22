package app.lumio.ui.screenshot

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.DpSize
import app.lensly.ui.screenshot.snapshot
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LumenTheme
import org.robolectric.RuntimeEnvironment

/** The qualifiers of a dark golden. The system is in night mode, so the Night palette shows. */
const val LUMIO_DARK_QUALIFIERS = "w720dp-h1600dp-port-night-mdpi"

/** The qualifiers of a light golden. The system is in day mode, so the Day palette shows. */
const val LUMIO_LIGHT_QUALIFIERS = "w720dp-h1600dp-port-notnight-mdpi"

/**
 * Renders [content] in Lumio's theme at the goldens' frame, with reduced motion on, so a static golden never catches a moving frame. The
 * palette follows the qualifiers of the test, as the system night mode does on a phone.
 */
fun ComposeContentTestRule.lumioSnapshot(
    name: String,
    settleMs: Long = 0L,
    beforeCapture: ComposeContentTestRule.() -> Unit = {},
    frame: DpSize? = null,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    reduceMotion()
    snapshot(
        name = name,
        settleMs = settleMs,
        beforeCapture = beforeCapture,
        theme = { LumenTheme { ClearAirTheme(content = it) } },
        frame = frame,
        fontScale = fontScale,
        content = content,
    )
}

/** Renders [content] in the Clear Air theme at the goldens' frame, with reduced motion on, as [lumioSnapshot] does for Lumen. */
fun ComposeContentTestRule.clearAirSnapshot(name: String, content: @Composable () -> Unit) {
    reduceMotion()
    snapshot(name = name, theme = { ClearAirTheme(content = it) }, content = content)
}

private fun reduceMotion() {
    Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
}
