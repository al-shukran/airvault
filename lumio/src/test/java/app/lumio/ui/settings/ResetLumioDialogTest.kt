package app.lumio.ui.settings

import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.theme.ClearAirTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Reset Lumio's dialog: the capsule erases only once the box is ticked, and Cancel erases nothing. */
@RunWith(RobolectricTestRunner::class)
class ResetLumioDialogTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theCapsuleStaysDisabledUntilTheBoxIsTicked() {
        var confirmed = 0
        compose.setContent { ClearAirTheme { ResetLumioDialog(onDismiss = {}, onConfirm = { confirmed++ }) } }

        compose.onNodeWithTag(RESET_CONFIRM_TAG).performClick()
        assertEquals(0, confirmed)

        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithTag(RESET_CONFIRM_TAG).performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun cancelDismissesWithoutConfirming() {
        var dismissed = 0
        var confirmed = 0
        compose.setContent {
            ClearAirTheme { ResetLumioDialog(onDismiss = { dismissed++ }, onConfirm = { confirmed++ }) }
        }

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
    }
}
