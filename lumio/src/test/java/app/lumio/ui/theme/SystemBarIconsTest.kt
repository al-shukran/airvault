package app.lumio.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The bars take dark icons on the Day palette and light icons otherwise, and the light icons come back when no Day screen is on show. */
@RunWith(RobolectricTestRunner::class)
class SystemBarIconsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dayScreenTakesDarkIconsAndLeavingRestoresLightIcons() {
        var showing by mutableStateOf(true)
        compose.setContent { if (showing) SystemBarIcons(lightBackground = true) }
        assertEquals(true, statusBarDarkIcons())

        compose.runOnIdle { showing = false }
        compose.waitForIdle()
        assertEquals(false, statusBarDarkIcons())
    }

    @Test
    fun darkIconsStayWhileAnotherDayScreenRemains() {
        var first by mutableStateOf(true)
        var second by mutableStateOf(true)
        compose.setContent {
            if (first) SystemBarIcons(lightBackground = true)
            if (second) SystemBarIcons(lightBackground = true)
        }

        compose.runOnIdle { first = false }
        compose.waitForIdle()
        assertEquals(true, statusBarDarkIcons())

        compose.runOnIdle { second = false }
        compose.waitForIdle()
        assertEquals(false, statusBarDarkIcons())
    }

    private fun statusBarDarkIcons(): Boolean {
        val window = compose.activity.window
        return WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars
    }
}
