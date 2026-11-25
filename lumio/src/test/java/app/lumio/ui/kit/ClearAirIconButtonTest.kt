package app.lumio.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** An icon button lays out as the mock's 44 dp box and presses in a 48 dp one, so the page does not move and the touch target is large enough (#1175). */
@RunWith(RobolectricTestRunner::class)
class ClearAirIconButtonTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theButtonTakesFortyFourDpOfLayoutAndPressesInFortyEight() {
        compose.setContent {
            ClearAirTheme { Box(Modifier.testTag("slot")) { ClearAirIconButton(glyph = ClearAirGlyph.BACK, label = "Back", onClick = {}) } }
        }

        compose.onNodeWithTag("slot").assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp)
        compose.onNodeWithContentDescription("Back").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
    }
}
