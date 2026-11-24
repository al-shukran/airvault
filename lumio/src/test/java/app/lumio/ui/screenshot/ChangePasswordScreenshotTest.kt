package app.lumio.ui.screenshot

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.lumio.PasswordChange
import app.lumio.ui.settings.ChangePasswordSettingsPage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val NEW_PASSWORD = "Vk8#Rw2!Lq6Zn4Tp9Xb"

/** The golden of Change Password on the Clear Air page after a wrong current password: the field's words, and the three fields kept as typed. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChangePasswordScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsChangePasswordWrongCurrent() = compose.lumioSnapshot(
        "settings_change_password_wrong_current",
        beforeCapture = {
            // The snapshot holds the clock still, but typing needs the field's focus to land first.
            mainClock.autoAdvance = true
            val fields = onAllNodes(hasSetTextAction())
            fields[0].performTextInput("not the password")
            fields[1].performTextInput(NEW_PASSWORD)
            fields[2].performTextInput(NEW_PASSWORD)
            onNode(hasText("Change Password") and hasClickAction()).performClick()
        },
    ) {
        ChangePasswordSettingsPage(
            offline = null,
            onSubmit = { _, _, _ -> PasswordChange.WrongCurrent },
            onChanged = {},
            onLocked = {},
            onUnavailable = {},
            onBack = {},
            onLock = {},
        )
    }
}
