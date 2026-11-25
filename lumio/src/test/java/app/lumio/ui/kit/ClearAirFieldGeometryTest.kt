package app.lumio.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The size of a [ClearAirField] against the locked mock's CSS box: 56 dp at least, 59 dp with a trailing 44 dp button (60 dp with the 2 dp edge of
 * an error), an 18 dp error row 6 dp below it, and 9 dp of room above it for the label. The density is exact, so the half dp of the edge does not round.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xhdpi")
class ClearAirFieldGeometryTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(error: String? = null, eye: Boolean = false, value: String = "") {
        compose.setContent {
            ClearAirTheme {
                Box(Modifier.testTag("slot")) {
                    ClearAirField(
                        label = "Password",
                        value = value,
                        onValueChange = {},
                        masked = eye,
                        error = error,
                        trailing = if (eye) ({ ClearAirEyeToggle(shown = false, onToggle = {}) }) else null,
                    )
                }
            }
        }
    }

    @Test
    fun aFieldWithoutAnActionIsFiftySixDpBelowItsLabelRoom() {
        show()
        compose.onNodeWithTag("slot").assertHeightIsEqualTo(9.dp + 56.dp)
    }

    @Test
    fun aFieldWithTheEyeIsFiftyNineDp() {
        show(eye = true)
        compose.onNodeWithTag("slot").assertHeightIsEqualTo(9.dp + 59.dp)
    }

    @Test
    fun anErrorMakesTheEdgeTwoDpAndAddsAnEighteenDpRowSixDpBelow() {
        show(error = "The passwords don't match", eye = true)
        compose.onNodeWithTag("slot").assertHeightIsEqualTo(9.dp + 60.dp + 6.dp + 18.dp)
        compose.onNodeWithText("The passwords don't match").assertHeightIsEqualTo(18.dp)
    }

    @Test
    fun theEyeStillPressesInFortyEight() {
        show(eye = true)
        compose.onNodeWithContentDescription("Show password").assertHeightIsEqualTo(48.dp)
    }
}
