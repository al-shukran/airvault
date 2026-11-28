package app.lumio.ui.screenshot

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import app.lumio.PasswordChange
import app.lumio.ui.firstrun.NewPasswordProblem
import app.lumio.ui.settings.ChangePasswordSettingsPage
import app.lumio.ui.settings.PasswordFormCopy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val NEW_PASSWORD = "Vk8#Rw2!Lq6Zn4Tp9Xb"

/**
 * Presses the button named [label] through its click action. A touch click starts the button's press ripple, and the capture then catches it at
 * whatever point of its fade the clock has reached, so the button's colour changed from run to run.
 */
private fun ComposeContentTestRule.submit(label: String) {
    onNode(hasText(label) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
}

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
            submit("Change Password")
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

    /** The Duress Password set that Security opens: its words, and the same three fields as Change Password. */
    @Test
    fun settingsDuressPassword() = compose.lumioSnapshot("settings_duress_password") {
        ChangePasswordSettingsPage(
            offline = null,
            onSubmit = { _, _, _ -> PasswordChange.Changed },
            onChanged = {},
            onLocked = {},
            onUnavailable = {},
            onBack = {},
            onLock = {},
            copy = PasswordFormCopy.Duress,
        )
    }

    /** A duress password that opens the real vault is refused on its own field. */
    @Test
    fun settingsDuressPasswordSameAsLumio() = compose.lumioSnapshot(
        "settings_duress_password_same_as_lumio",
        beforeCapture = {
            mainClock.autoAdvance = true
            val fields = onAllNodes(hasSetTextAction())
            fields[0].performTextInput(NEW_PASSWORD)
            fields[1].performTextInput(NEW_PASSWORD)
            fields[2].performTextInput(NEW_PASSWORD)
            submit("Set Duress Password")
        },
    ) {
        ChangePasswordSettingsPage(
            offline = null,
            onSubmit = { _, _, _ -> PasswordChange.NewPassword(NewPasswordProblem.SameAsLumio) },
            onChanged = {},
            onLocked = {},
            onUnavailable = {},
            onBack = {},
            onLock = {},
            copy = PasswordFormCopy.Duress,
        )
    }
}
