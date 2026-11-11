package app.lensly.ui.home

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Home aperture's turn over a paused clock. The aperture is drawn from its turn alone, so a turn that never moves draws the same frame
 * at every moment: that is the static hero at animator scale 0. With animations on, the turn advances, and one full turn takes at least
 * eight seconds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp-port-mdpi")
class HomeHeroTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun restoreAnimators() {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    @Test
    fun theTurnHoldsAtZeroWhenTheSystemTurnsAnimationsOff() {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val turns = mutableListOf<Float>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LenslyTheme {
                TurnProbe(turns)
            }
        }
        compose.waitForIdle()
        val atStart = turns.last()

        compose.mainClock.advanceTimeBy(4_000)
        compose.waitForIdle()

        assertEquals(atStart, turns.last(), "the turn at t=4 s equals the turn at t=0")
        assertTrue(turns.all { it == 0f }, "the turn stays at 0 at animator scale 0: $turns")
    }

    @Test
    fun theTurnTakesAtLeastEightSecondsForOneCycle() {
        val turns = mutableListOf<Float>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LenslyTheme {
                TurnProbe(turns)
            }
        }
        compose.waitForIdle()

        compose.mainClock.advanceTimeBy(8_000)
        compose.waitForIdle()

        // A cycle of 24 s turns 120 degrees in 8 s. A cycle of 8 s or less would have come back round to 0 or 360.
        val atEightSeconds = turns.last()
        assertTrue(atEightSeconds in 100f..140f, "after 8 s the turn is about 120 degrees, got $atEightSeconds")
    }

    /** Records the turn the aperture is drawn with each time it is composed, so a test can follow the turn over the paused clock. */
    @Composable
    private fun TurnProbe(turns: MutableList<Float>) {
        // The turn is read during composition, so the probe is composed again each time the turn moves.
        val turn = rememberSpinDegrees().value
        SideEffect { turns += turn }
    }
}
