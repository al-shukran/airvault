package app.lensly.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The gesture list: choosing another gesture reports it, and the current one is marked and cannot be chosen again. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class EntryGestureScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showList(current: EntryGesture, onChoose: (EntryGesture) -> Unit) {
        compose.setContent {
            LenslyTheme {
                EntryGestureScreen(current = current, onChoose = onChoose, onBack = {}, onLock = {})
            }
        }
    }

    @Test
    fun choosingAnotherGestureReportsIt() {
        var chosen: EntryGesture? = null
        showList(EntryGesture.DOUBLE_TAP_HOLD) { chosen = it }

        compose.onNodeWithText(EntryGesture.PINCH_TRIPLE_TAP.title).performClick()

        assertEquals(EntryGesture.PINCH_TRIPLE_TAP, chosen)
    }

    @Test
    fun theCurrentGestureIsMarkedAndCannotBeChosenAgain() {
        var chosen: EntryGesture? = null
        showList(EntryGesture.THUMBNAIL_HOLD_SWIPE) { chosen = it }

        compose.onNodeWithText("${EntryGesture.THUMBNAIL_HOLD_SWIPE.title} (current)").assertIsDisplayed()
        compose.onNodeWithText("${EntryGesture.THUMBNAIL_HOLD_SWIPE.title} (current)").performClick()

        assertNull(chosen)
    }

    @Test
    fun everyGestureIsListedWithItsDescription() {
        showList(EntryGesture.DOUBLE_TAP_HOLD) {}

        for (gesture in EntryGesture.entries) {
            compose.onNodeWithText(gesture.description).assertIsDisplayed()
        }
    }
}
