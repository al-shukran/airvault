package app.lensly.ui.profile

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.app.AccountDraft
import app.lensly.app.ProfileColour
import app.lensly.app.ProfileIcon
import app.lensly.ui.components.waitUntilKeyboardHidden
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The account profile keyboard. It opens hidden, with SAVE in its place. A tap on a field shows it, and a tap outside the fields,
 * the keyboard's DONE or the system Back hides it. The Back test has a handler above the editor, which stands in for the navigation
 * that Back falls through to once the keyboard is down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class AccountProfileScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var leftEditor = 0
    private var saved = 0

    @Test
    fun theEditorOpensWithTheKeyboardHiddenAndSaveShowing() {
        setEditor()

        compose.onNodeWithContentDescription("Done").assertDoesNotExist()
        compose.onNodeWithText("SAVE").assertExists()
    }

    @Test
    fun aTapOnAFieldShowsTheKeyboardAndATapOutsideHidesIt() {
        setEditor()

        compose.onNodeWithText("No label").performClick()
        compose.onNodeWithContentDescription("Done").assertExists()

        compose.onNodeWithText("ACCOUNT PROFILE").performClick()
        compose.waitUntilKeyboardHidden()
        compose.onNodeWithText("SAVE").assertExists()
    }

    @Test
    fun backHidesTheKeyboardBeforeItLeavesTheEditor() {
        setEditor()
        compose.onNodeWithText("No label").performClick()
        // The keyboard's Back handler is added once the screen has settled, so let it settle before the press.
        compose.waitForIdle()

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.waitUntilKeyboardHidden()
        assertEquals(0, leftEditor)

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals(1, leftEditor)
    }

    @Test
    fun typingGoesToTheTappedFieldAndDoneHidesTheKeyboardWithoutSaving() {
        setEditor()
        compose.onNodeWithText("No label").performClick()

        compose.onNodeWithContentDescription("x").performClick()
        compose.onNodeWithText("x_").assertExists()

        compose.onNodeWithContentDescription("Done").performClick()
        compose.waitUntilKeyboardHidden()
        assertEquals(0, saved)

        compose.onNodeWithText("SAVE").performClick()
        assertEquals(1, saved)
    }

    /** Hosts the editor with its draft in state, under a Back handler that counts leaving the editor. */
    private fun setEditor() {
        compose.setContent {
            LenslyTheme {
                BackHandler { leftEditor++ }
                var draft by remember {
                    mutableStateOf(AccountDraft(name = "", colour = ProfileColour.RED, icon = ProfileIcon.STAR, label = "", group = ""))
                }
                AccountProfileScreen(
                    draft = draft,
                    autoName = "Seed wallet 1",
                    groups = emptyList(),
                    onFocus = { field -> draft = draft.focusOn(field) },
                    onKey = { key -> draft = draft.typing(key) },
                    onBackspace = { draft = draft.backspace() },
                    onPickColour = {},
                    onPickIcon = {},
                    onPickGroup = {},
                    onResetName = {},
                    onResetStyle = {},
                    onSave = { saved++ },
                )
            }
        }
    }
}
