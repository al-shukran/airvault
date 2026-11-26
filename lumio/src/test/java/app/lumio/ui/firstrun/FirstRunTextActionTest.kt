package app.lumio.ui.firstrun

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The text action under a first-run capsule is one 24 dp line tall, as the mock draws it, and keeps its 48 dp touch target: a tap up to 12 dp above or below
 * its 24 dp line still lands on it, so the visible text does not have to move for the target.
 */
@RunWith(RobolectricTestRunner::class)
class FirstRunTextActionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aTapJustOutsideTheLineStillLandsOnTheAction() {
        var taps = 0
        compose.setContent {
            ClearAirTheme { Box(modifier = Modifier.padding(40.dp)) { FirstRunTextAction(text = "Not Now", onClick = { taps++ }) } }
        }

        compose.onNodeWithText("Not Now").performTouchInput { click(center.copy(y = center.y + 11.dp.toPx())) }
        compose.onNodeWithText("Not Now").performTouchInput { click(center.copy(y = center.y - 11.dp.toPx())) }

        assertEquals(2, taps)
    }
}
