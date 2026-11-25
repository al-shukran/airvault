package app.lumio.ui.firstrun

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.lensly.ui.theme.LumenTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A first-run page keeps clear of a navigation bar at its side, as in landscape with three-button navigation. */
@RunWith(RobolectricTestRunner::class)
class FirstRunInsetsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theTitleClearsTheNavigationBarAtTheSide() {
        compose.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false) }
        compose.setContent { LumenTheme { app.lensly.ui.theme.ClearAirTheme { WelcomeScreen(onNext = {}) } } }
        val bar = 96
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(bar, 0, 0, 0)).build()
            ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets)
        }
        compose.waitForIdle()

        // The test runs at 160 dpi, so a pixel is a dp.
        val left = compose.onNodeWithText("Welcome to AirVault").getUnclippedBoundsInRoot().left.value
        assertTrue(left >= bar, "The title starts at $left dp, under the $bar dp bar")
    }
}
