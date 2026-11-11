package app.lensly.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.app.AccountDraft
import app.lensly.app.ProfileColour
import app.lensly.app.ProfileField
import app.lensly.app.ProfileIcon
import app.lensly.app.StyleEdits
import app.lensly.app.StyleField
import app.lensly.app.TextEdit
import app.lensly.app.WalletProfileDraft
import app.lensly.ui.profile.AccountProfileScreen
import app.lensly.ui.profile.StyleAccountsScreen
import app.lensly.ui.profile.WalletProfileScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The profile editors, one snapshot each: an account with its name being typed, an account with no field focused (the keyboard
 * hidden and SAVE shown), a wallet with groups and one being added, and the batch style editor with a colour picked and a label set
 * to be cleared. Record with :app:recordRoborazziDebug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileScreensScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun accountProfileWithItsNameBeingTyped() = compose.snapshot("profile_edit_account") {
        AccountProfileScreen(
            draft = AccountDraft(
                name = "Savings",
                colour = ProfileColour.GREEN,
                icon = ProfileIcon.STAR,
                label = "Rent",
                group = "Home",
                focus = ProfileField.NAME,
            ),
            autoName = "Seed wallet 1",
            groups = listOf("Home", "Work"),
            onFocus = {},
            onKey = {},
            onBackspace = {},
            onPickColour = {},
            onPickIcon = {},
            onPickGroup = {},
            onResetName = {},
            onResetStyle = {},
            onSave = {},
        )
    }

    @Test
    fun accountProfileWithNoFieldFocused() = compose.snapshot("profile_edit_account_idle") {
        AccountProfileScreen(
            draft = AccountDraft(
                name = "Savings",
                colour = ProfileColour.GREEN,
                icon = ProfileIcon.STAR,
                label = "Rent",
                group = "Home",
            ),
            autoName = "Seed wallet 1",
            groups = listOf("Home", "Work"),
            onFocus = {},
            onKey = {},
            onBackspace = {},
            onPickColour = {},
            onPickIcon = {},
            onPickGroup = {},
            onResetName = {},
            onResetStyle = {},
            onSave = {},
        )
    }

    @Test
    fun walletProfileWithAGroupBeingAdded() = compose.snapshot("profile_edit_wallet") {
        WalletProfileScreen(
            draft = WalletProfileDraft(
                name = "Everyday",
                colour = ProfileColour.ORANGE,
                icon = ProfileIcon.HOME,
                label = "Family",
                groups = listOf("Work", "Home"),
                pendingGroup = "Car",
                focus = ProfileField.GROUP,
            ),
            onFocus = {},
            onKey = {},
            onBackspace = {},
            onPickColour = {},
            onPickIcon = {},
            onRemoveGroup = {},
            onResetStyle = {},
            onSave = {},
        )
    }

    @Test
    fun batchStyleWithAColourPickedAndALabelSet() = compose.snapshot("profile_style_accounts") {
        StyleAccountsScreen(
            count = 4,
            edits = StyleEdits(
                colour = ProfileColour.TEAL,
                label = TextEdit.Set("Rent"),
                group = TextEdit.Clear,
                focus = StyleField.LABEL,
            ),
            onFocus = {},
            onKey = {},
            onBackspace = {},
            onPickColour = {},
            onPickIcon = {},
            onClear = {},
            onResetStyle = {},
            onSave = {},
        )
    }
}
