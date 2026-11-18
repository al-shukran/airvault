package app.lumio.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LumenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The tap of [lumioPress]: a tap fires once, a drag does not, and a disabled surface neither fires nor offers the click action. The press itself is
 * drawn, so its golden is the check for it (LumioKitScreenshotTest, the pressed goldens).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LumioPressTest {

    @get:Rule
    val compose = createComposeRule()

    private fun ComposeContentTestRule.setPressable(enabled: Boolean = true, onClick: () -> Unit) {
        setContent {
            LumenTheme {
                Box(Modifier.size(200.dp).testTag(TAG).lumioPress(scale = SCALE, enabled = enabled, onClick = onClick))
            }
        }
    }

    @Test
    fun tapFiresOnClickOnce() {
        var taps = 0
        compose.setPressable { taps++ }
        compose.onNodeWithTag(TAG).performClick()
        assertEquals(1, taps)
    }

    @Test
    fun dragDoesNotTap() {
        var taps = 0
        compose.setPressable { taps++ }
        compose.onNodeWithTag(TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, 300f))
            up()
        }
        assertEquals(0, taps)
    }

    @Test
    fun disabledDoesNotTapAndSaysSo() {
        var taps = 0
        compose.setPressable(enabled = false) { taps++ }
        compose.onNodeWithTag(TAG).performClick()
        compose.onNodeWithTag(TAG).assertIsNotEnabled()
        assertEquals(0, taps)
    }

    @Test
    fun enabledExposesTheClickActionToAccessibility() {
        compose.setPressable { }
        compose.onNodeWithTag(TAG).assertHasClickAction()
    }

    private companion object {
        const val TAG = "pressable"
        const val SCALE = 0.5f
    }
}
