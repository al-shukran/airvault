package app.lensly.ui.theme

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
import org.robolectric.annotation.Config

/** The bars take dark icons on the Day palette and light icons otherwise, and the light icons come back when no Day screen is on show. */
@RunWith(RobolectricTestRunner::class)
class SystemBarIconsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dayScreenTakesDarkIconsAndLeavingRestoresLightIcons() {
        var showing by mutableStateOf(true)
        compose.setContent { if (showing) SystemBarIcons(lightBackground = true) }
        assertDarkIcons(true)

        compose.runOnIdle { showing = false }
        compose.waitForIdle()
        assertDarkIcons(false)
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
        assertDarkIcons(true)

        compose.runOnIdle { second = false }
        compose.waitForIdle()
        assertDarkIcons(false)
    }

    @Test
    @Config(qualifiers = "notnight")
    fun lumenOnADayDeviceTakesDarkIcons() {
        compose.setContent { LumenTheme { } }
        assertDarkIcons(true)
    }

    @Test
    @Config(qualifiers = "night")
    fun lumenOnANightDeviceTakesLightIcons() {
        compose.setContent { LumenTheme { } }
        assertDarkIcons(false)
    }

    /** The cover is always dark, so the dark icons of a Day screen go when the cover takes over from it. */
    @Test
    @Config(qualifiers = "notnight")
    fun coverAfterADayScreenTakesLightIcons() {
        var day by mutableStateOf(true)
        compose.setContent { if (day) LumenTheme { } else CoverTheme { } }
        assertDarkIcons(true)

        compose.runOnIdle { day = false }
        compose.waitForIdle()
        assertDarkIcons(false)
    }

    /** The status bar and the navigation bar take the same icons. */
    private fun assertDarkIcons(dark: Boolean) {
        val window = compose.activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        assertEquals("status bar", dark, controller.isAppearanceLightStatusBars)
        assertEquals("navigation bar", dark, controller.isAppearanceLightNavigationBars)
    }
}
