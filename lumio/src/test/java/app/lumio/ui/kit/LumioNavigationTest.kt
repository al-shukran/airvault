package app.lumio.ui.kit

import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import app.lumio.LumioRoute
import app.lensly.ui.theme.LumenTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Reduced motion reaches the NavHost (section 3.6): its transitions are cuts, and the mirror follows the system setting. */
@RunWith(RobolectricTestRunner::class)
class LumioNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController

    @After
    fun resetMirror() {
        NavReducedMotion.reduced = false
    }

    @Test
    fun cutWhenReducedReturnsTheCutAndNeverBuildsTheMotion() {
        var built = false

        val result = cutWhenReduced(reduced = true, cut = "cut") {
            built = true
            "motion"
        }

        assertEquals("cut", result)
        assertFalse(built)
    }

    @Test
    fun cutWhenNotReducedBuildsTheMotion() {
        assertEquals("motion", cutWhenReduced(reduced = false, cut = "cut") { "motion" })
    }

    @Test
    fun mirrorIsOnWhenTheAnimatorScaleIsZero() {
        setAnimatorScale(0f)
        showScreens()

        assertTrue(NavReducedMotion.reduced)
    }

    @Test
    fun mirrorIsOffAtTheDefaultScale() {
        setAnimatorScale(1f)
        showScreens()

        assertFalse(NavReducedMotion.reduced)
    }

    @Test
    fun reducedMotionMakesANavigationACut() {
        setAnimatorScale(0f)
        showScreens()

        navigateToSettings()
        compose.mainClock.advanceTimeBy(50)

        compose.onNodeWithText("Wallets screen").assertDoesNotExist()
        compose.onNodeWithText("Settings screen").assertExists()
    }

    /** The control for the cut above: with motion on, the leaving screen is still on show at 50 ms, and the Settings screen has arrived by 400 ms. */
    @Test
    fun motionKeepsTheLeavingScreenOnShowDuringTheFade() {
        setAnimatorScale(1f)
        showScreens()

        navigateToSettings()
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithText("Wallets screen").assertExists()

        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithText("Wallets screen").assertDoesNotExist()
        compose.onNodeWithText("Settings screen").assertExists()
    }

    private fun showScreens() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LumenTheme {
                MirrorNavMotion()
                nav = rememberNavController()
                NavHost(navController = nav, startDestination = LumioRoute.WALLETS) {
                    lumioComposable(LumioRoute.WALLETS) { Text("Wallets screen") }
                    lumioComposable(LumioRoute.SETTINGS) { Text("Settings screen") }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun navigateToSettings() {
        compose.runOnIdle { nav.navigate(LumioRoute.SETTINGS) }
    }

    private fun setAnimatorScale(scale: Float) {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
    }
}
