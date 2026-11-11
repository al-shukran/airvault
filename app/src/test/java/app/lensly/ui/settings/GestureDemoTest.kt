package app.lensly.ui.settings

import android.provider.Settings
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The demo of each gesture: its frame and its caption are shown, and no placeholder is left. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class GestureDemoTest {

    @get:Rule
    val compose = createComposeRule()

    private fun assertDemoShown(gesture: EntryGesture) {
        compose.setContent {
            LenslyTheme {
                GestureDemo(gesture = gesture, modifier = Modifier)
            }
        }

        compose.onNodeWithTag("gesture-demo-${gesture.storedId}").assertIsDisplayed()
        compose.onNodeWithText(gesture.demoCaption).assertIsDisplayed()
        compose.onAllNodesWithText("coming soon", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test
    fun doubleTapHoldShowsItsDemoAndCaption() = assertDemoShown(EntryGesture.DOUBLE_TAP_HOLD)

    @Test
    fun thumbnailHoldSwipeShowsItsDemoAndCaption() = assertDemoShown(EntryGesture.THUMBNAIL_HOLD_SWIPE)

    @Test
    fun pinchTripleTapShowsItsDemoAndCaption() = assertDemoShown(EntryGesture.PINCH_TRIPLE_TAP)

    @Test
    fun switchThenShutterHoldShowsItsDemoAndCaption() = assertDemoShown(EntryGesture.SWITCH_THEN_SHUTTER_HOLD)

    @Test
    fun animatorsAreOffOnlyWhenTheSystemScaleIsZero() {
        val context = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertTrue(animatorsDisabled(context))

        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertFalse(animatorsDisabled(context))
    }
}
