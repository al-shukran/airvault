package app.lumio

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A Lumio screen takes the status and navigation bar icons of its theme: dark on the Day palette, light on the Night one. */
@RunWith(RobolectricTestRunner::class)
class LumioBarIconsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "notnight")
    fun lumioScreenOnADayDeviceTakesDarkBarIcons() {
        compose.setContent { LumioBackdrop(NetworkSelection()) { } }
        assertDarkBarIcons(true)
    }

    @Test
    @Config(qualifiers = "night")
    fun lumioScreenOnANightDeviceTakesLightBarIcons() {
        compose.setContent { LumioBackdrop(NetworkSelection()) { } }
        assertDarkBarIcons(false)
    }

    /** The status bar and the navigation bar take the same icons. */
    private fun assertDarkBarIcons(dark: Boolean) {
        val window = compose.activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        assertEquals("status bar", dark, controller.isAppearanceLightStatusBars)
        assertEquals("navigation bar", dark, controller.isAppearanceLightNavigationBars)
    }
}
