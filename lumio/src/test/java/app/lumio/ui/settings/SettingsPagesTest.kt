package app.lumio.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.flow.PasswordCheck
import app.lensly.ui.theme.LumenTheme
import app.lumio.PasswordChange
import app.lumio.data.config.AutoLock
import app.lumio.data.config.DisplaySettings
import app.lumio.data.config.LockSettings
import app.lumio.data.config.TransactionSettings
import app.lumio.ui.firstrun.NewPasswordProblem
import app.lumio.evm.EvmSettings
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Each settings page is its own screen. None of them has a Save button, because each control applies as the owner changes it. A page's controls
 * report their changes to the caller at once.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsPagesTest {

    @get:Rule
    val compose = createComposeRule()

    private fun assertNoSave(page: @Composable () -> Unit) {
        compose.setContent { ClearAirHarness { page() } }
        compose.onAllNodesWithText("Save", ignoreCase = true).assertCountEquals(0)
    }

    @Test
    fun theDisplayPageHasNoSave() = assertNoSave {
        DisplaySettingsPage(
            settings = DisplaySettings(), notice = null, onShowTotal = {}, onShowAssets = {}, onCurrency = {}, onTheme = {}, onSmallBalance = {},
            offline = null, onLock = {}, onBack = {},
        )
    }

    @Test
    fun theNetworksPageHasNoSave() = assertNoSave {
        NetworksSettingsPage(
            offline = null, onLock = {}, onBack = {},
        ) {
            EvmSettingsSection(
                settings = EvmSettings(),
                chains = emptyList(),
                logos = emptyMap(),
                busy = false,
                notice = null,
                onAddressCount = {},
                onEnabled = { _, _ -> },
                onNetwork = {},
                onAddNetwork = {},
                onBitcoin = {},
            )
        }
    }

    @Test
    fun theWalletsPageHasNoSave() = assertNoSave {
        WalletsSettingsPage(offline = null, onLock = {}, onBack = {}, wallets = { Text("Pair from Lensly") })
    }

    @Test
    fun theSecurityPageHasNoSaveAndOpensTheGestureChoice() {
        var opened = false
        compose.setContent {
            ClearAirHarness {
                SecuritySettingsPage(
                    lock = LockSettings(),
                    biometric = BiometricSwitch(on = false, canEnable = true),
                    onEntryGesture = { opened = true },
                    onChangePassword = {},
                    onBiometric = {},
                    checkPassword = { PasswordCheck.Accepted },
                    onAutoLock = {},
                    offline = null, onLock = {}, onBack = {},
                )
            }
        }

        compose.onAllNodesWithText("Save", ignoreCase = true).assertCountEquals(0)
        compose.onNodeWithText("Hidden Entry Gesture").performClick()
        assertEquals(true, opened)
    }

    @Test
    fun theSecurityPageOpensTheAppPasswordChange() {
        var opened = false
        compose.setContent {
            ClearAirHarness {
                SecuritySettingsPage(
                    lock = LockSettings(),
                    biometric = BiometricSwitch(on = false, canEnable = true),
                    onEntryGesture = {},
                    onChangePassword = { opened = true },
                    onBiometric = {},
                    checkPassword = { PasswordCheck.Accepted },
                    onAutoLock = {},
                    offline = null, onLock = {}, onBack = {},
                )
            }
        }

        compose.onNodeWithText("Password").performScrollTo().performClick()
        assertEquals(true, opened)
    }

    @Test
    fun theDeviceProtectionSwitchOnSecurityReportsTheOwnersChoice() {
        var changed: Boolean? = null
        compose.setContent {
            ClearAirHarness {
                SecuritySettingsPage(
                    lock = LockSettings(),
                    biometric = BiometricSwitch(on = false, canEnable = true),
                    onEntryGesture = {},
                    onChangePassword = {},
                    onBiometric = { changed = it },
                    checkPassword = { PasswordCheck.Accepted },
                    onAutoLock = {},
                    offline = null, onLock = {}, onBack = {},
                )
            }
        }

        compose.onNodeWithText("Device Protection").performScrollTo().performClick()
        assertEquals(true, changed)
    }

    @Test
    fun theDeviceProtectionSwitchSaysWhyItCannotBeTurnedOn() {
        compose.setContent {
            ClearAirHarness {
                SecuritySettingsPage(
                    lock = LockSettings(),
                    biometric = BiometricSwitch(on = false, canEnable = false),
                    onEntryGesture = {},
                    onChangePassword = {},
                    onBiometric = {},
                    checkPassword = { PasswordCheck.Accepted },
                    onAutoLock = {},
                    offline = null, onLock = {}, onBack = {},
                )
            }
        }

        compose.onNodeWithText(BIOMETRIC_UNAVAILABLE_REASON).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theAutoLockRowShowsItsChoiceInWords() {
        compose.setContent {
            ClearAirHarness {
                SecuritySettingsPage(
                    lock = LockSettings(AutoLock.IMMEDIATELY),
                    biometric = BiometricSwitch(on = false, canEnable = true),
                    onEntryGesture = {},
                    onChangePassword = {},
                    onBiometric = {},
                    checkPassword = { PasswordCheck.Accepted },
                    onAutoLock = {},
                    offline = null, onLock = {}, onBack = {},
                )
            }
        }

        // The page scrolls: the lock section sits below the fold of the test's small window, so the row is scrolled to first.
        compose.onNodeWithText("Immediately").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Each change asks for your current password first.").performScrollTo().assertIsDisplayed()
    }

    /** A change of Auto-Lock is kept only when the App Password is typed in the step that the row opens. The sheet itself is not driven here. */
    @Test
    fun theAutoLockChangeAcceptsOnlyTheAppPassword() {
        var accepted = false
        compose.setContent {
            ClearAirHarness {
                AutoLockPasswordDialog(
                    choice = AutoLock.IMMEDIATELY,
                    checkPassword = { if (it.concatToString() == "the right one") PasswordCheck.Accepted else PasswordCheck.Rejected },
                    onClose = {},
                    onAccepted = { accepted = true },
                )
            }
        }

        compose.onNodeWithText("Enter your current password to set Auto-Lock to Immediately.").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("not the password")
        compose.onNodeWithText("Change").assertIsEnabled()
        compose.onNodeWithText("Change").performClick()
        // The field's error is drawn with its glyph in front, as "! That isn't your current password".
        compose.onNodeWithText(WRONG_CURRENT_PASSWORD, substring = true).assertExists()
        assertFalse(accepted)
        compose.onNode(hasSetTextAction()).performTextInput("the right one")
        compose.onNodeWithText("Change").performClick()
        assertTrue(accepted)
    }

    /** A wrong current password leaves all three fields as typed, so the owner corrects the one that is wrong and only the error shows. */
    @Test
    fun aWrongCurrentPasswordKeepsTheFieldsAsTyped() {
        var tried: String? = null
        compose.setContent {
            ClearAirHarness {
                ChangePasswordSettingsPage(
                    offline = null,
                    onSubmit = { current, _, _ ->
                        tried = current
                        PasswordChange.WrongCurrent
                    },
                    onChanged = {}, onLocked = {}, onUnavailable = {}, onBack = {}, onLock = {},
                )
            }
        }

        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("not the password")
        fields[1].performTextInput("Vk8#Rw2!Lq6Zn4Tp9Xb")
        fields[2].performTextInput("Vk8#Rw2!Lq6Zn4Tp9Xb")
        compose.onNode(hasText("Change Password") and hasClickAction()).performClick()

        assertEquals("not the password", tried)
        compose.onNodeWithText(WRONG_CURRENT_PASSWORD, substring = true).assertExists()
        compose.onNodeWithText("Fill in all three fields to continue").assertDoesNotExist()
        compose.onNode(hasText("Change Password") and hasClickAction()).assertExists()
    }

    /** Once a change is made, no field keeps its password: the page asks for all three again. */
    @Test
    fun aChangedPasswordClearsAllThreeFields() = assertFieldsClearedAfter(PasswordChange.Changed, "Vk8#Rw2!Lq6Zn4Tp9Xb")

    /** A refused submit clears all three fields too, so no typed password stays on the page. */
    @Test
    fun aRefusedSubmitClearsAllThreeFields() =
        assertFieldsClearedAfter(PasswordChange.NewPassword(NewPasswordProblem.Mismatch), "Vk8#Rw2!Lq6Zn4Tp9Xc") {
            compose.onNodeWithText("The passwords don't match", substring = true).assertExists()
        }

    private fun assertFieldsClearedAfter(outcome: PasswordChange, repeat: String, extra: () -> Unit = {}) {
        compose.setContent {
            ClearAirHarness {
                ChangePasswordSettingsPage(
                    offline = null,
                    onSubmit = { _, _, _ -> outcome },
                    onChanged = {}, onLocked = {}, onUnavailable = {}, onBack = {}, onLock = {},
                )
            }
        }

        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("not the password")
        fields[1].performTextInput("Vk8#Rw2!Lq6Zn4Tp9Xb")
        fields[2].performTextInput(repeat)
        compose.onNode(hasText("Change Password") and hasClickAction()).performClick()

        extra()
        compose.onNodeWithText("Fill in all three fields to continue").assertExists()
    }

    @Test
    fun theAboutPageHasNoSave() = assertNoSave {
        AboutSettingsPage(version = "0.1.0", onLicences = {}, offline = null, onLock = {}, onBack = {})
    }

    @Test
    fun theTransactionsPageHasNoSave() = assertNoSave {
        TransactionsSettingsPage(
            settings = TransactionSettings(), notice = null, onHideSpam = {}, onLastChance = {},
            offline = null, onLock = {}, onBack = {}, comments = {},
        )
    }

    @Test
    fun theHideSpamSwitchReportsTheOwnersChoiceAndTheLineShowsThePause() {
        var hide: Boolean? = null
        compose.setContent {
            ClearAirHarness {
                TransactionsSettingsPage(
                    settings = TransactionSettings(lastChanceSeconds = 5), notice = null,
                    onHideSpam = { hide = it }, onLastChance = {},
                    offline = null, onLock = {}, onBack = {}, comments = {},
                )
            }
        }

        // The section title is an eyebrow, so it is drawn in capitals, and the pause is its value.
        compose.onNodeWithText("LAST CHANCE BEFORE SENDING").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("5 s").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Hide Spam").performScrollTo().performClick()
        assertEquals(false, hide)
    }
}

/** The theme a settings page is drawn in in the app: the Clear Air theme, under the Lumen theme that wraps Lumio. */
@androidx.compose.runtime.Composable
private fun ClearAirHarness(content: @androidx.compose.runtime.Composable () -> Unit) {
    LumenTheme { app.lensly.ui.theme.ClearAirTheme(content = content) }
}
