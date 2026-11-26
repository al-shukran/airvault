package app.lumio.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
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
import androidx.compose.ui.unit.dp
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.flow.PasswordCheck
import app.lensly.ui.theme.LumenTheme
import app.lumio.PasswordChange
import app.lumio.data.config.AutoLock
import app.lumio.data.config.DisplaySettings
import app.lumio.data.config.LockSettings
import app.lumio.data.config.TransactionSettings
import app.lumio.ui.firstrun.NewPasswordProblem
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettings
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
    fun aNetworkSwitchIsNamedForTalkBackAndIsAtLeast48DpHigh() {
        var turned: Boolean? = null
        compose.setContent {
            ClearAirHarness {
                EvmSettingsSection(
                    settings = EvmSettings(enabled = setOf(1L)),
                    chains = listOf(EvmChain(1L, "Ethereum", "ETH", 18, testnet = false, explorerUrl = null)),
                    logos = emptyMap(),
                    busy = false,
                    notice = null,
                    onAddressCount = {},
                    onEnabled = { _, on -> turned = on },
                    onNetwork = {},
                    onAddNetwork = {},
                    onBitcoin = {},
                )
            }
        }

        val switch = compose.onNode(hasContentDescription("Ethereum network"))
        switch.assertIsOn().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        switch.performClick()
        assertEquals(false, turned)
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
                    duressSet = false,
                    onDuress = {},
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
    fun theSecurityPageShowsTheDuressRowNotSetAndOpensItsSet() {
        var opened = false
        compose.setContent { ClearAirHarness { SecurityPage(duressSet = false, onDuress = { opened = true }) } }

        compose.onNodeWithText("Not Set").assertExists()
        compose.onNodeWithText("Duress Password").performScrollTo().performClick()
        assertEquals(true, opened)
    }

    @Test
    fun theSecurityPageShowsTheDuressRowSetOnceItIsSet() {
        compose.setContent { ClearAirHarness { SecurityPage(duressSet = true, onDuress = {}) } }

        compose.onNodeWithText("Set").assertExists()
        compose.onAllNodesWithText("Not Set").assertCountEquals(0)
    }

    @Test
    fun theDuressPageRunsTheSetWithItsWordsAndShowsASamePasswordOnItsField() {
        var tried: Triple<String, String, String>? = null
        compose.setContent {
            ClearAirHarness {
                ChangePasswordSettingsPage(
                    offline = null,
                    onSubmit = { current, password, repeat ->
                        tried = Triple(current, password, repeat)
                        PasswordChange.NewPassword(NewPasswordProblem.SameAsLumio)
                    },
                    onChanged = {}, onLocked = {}, onUnavailable = {}, onBack = {}, onLock = {},
                    copy = PasswordFormCopy.Duress,
                )
            }
        }

        compose.onNodeWithText("Repeat Duress Password").assertExists()
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("the real password")
        fields[1].performTextInput("Vk8#Rw2!Lq6Zn4Tp9Xb")
        fields[2].performTextInput("Vk8#Rw2!Lq6Zn4Tp9Xb")
        compose.onNode(hasText("Set Duress Password") and hasClickAction()).performClick()

        assertEquals(Triple("the real password", "Vk8#Rw2!Lq6Zn4Tp9Xb", "Vk8#Rw2!Lq6Zn4Tp9Xb"), tried)
        compose.onNodeWithText("That's your Lumio password. Use a different one.").assertExists()
    }

    @Composable
    private fun SecurityPage(duressSet: Boolean, onDuress: () -> Unit) {
        SecuritySettingsPage(
            lock = LockSettings(),
            biometric = BiometricSwitch(on = false, canEnable = true),
            onEntryGesture = {},
            onChangePassword = {},
            duressSet = duressSet,
            onDuress = onDuress,
            onBiometric = {},
            checkPassword = { PasswordCheck.Accepted },
            onAutoLock = {},
            offline = null, onLock = {}, onBack = {},
        )
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
                    duressSet = false,
                    onDuress = {},
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
                    duressSet = false,
                    onDuress = {},
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
                    duressSet = false,
                    onDuress = {},
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
    fun theDeviceProtectionRowTellsHowToOpenThePromptOnlyWhileItIsOn() {
        var biometric by mutableStateOf(BiometricSwitch(on = false, canEnable = true))
        compose.setContent {
            ClearAirHarness {
                SecuritySettingsPage(
                    lock = LockSettings(),
                    biometric = biometric,
                    onEntryGesture = {},
                    onChangePassword = {},
                    duressSet = false,
                    onDuress = {},
                    onBiometric = {},
                    checkPassword = { PasswordCheck.Accepted },
                    onAutoLock = {},
                    offline = null, onLock = {}, onBack = {},
                )
            }
        }

        compose.onNodeWithText(BIOMETRIC_UNLOCK_HELP).assertDoesNotExist()
        compose.onNodeWithText("Fingerprint or face").performScrollTo().assertIsDisplayed()
        biometric = BiometricSwitch(on = true, canEnable = true)
        compose.onNodeWithText(BIOMETRIC_UNLOCK_HELP).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Fingerprint or face").assertDoesNotExist()
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
                    duressSet = false,
                    onDuress = {},
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

    /** The Auto-Lock sheet opens over the page, so its choices are tapped where they show, and a choice asks for the App Password before it applies. */
    @Test
    fun theAutoLockSheetOpensOverThePageAndAsksForThePassword() {
        var chosen: AutoLock? = null
        compose.setContent {
            ClearAirHarness {
                SecuritySettingsPage(
                    lock = LockSettings(AutoLock.AFTER_ONE_MINUTE),
                    biometric = BiometricSwitch(on = false, canEnable = true),
                    onEntryGesture = {},
                    onChangePassword = {},
                    duressSet = false,
                    onDuress = {},
                    onBiometric = {},
                    checkPassword = { PasswordCheck.Accepted },
                    onAutoLock = { chosen = it },
                    offline = null, onLock = {}, onBack = {},
                )
            }
        }

        compose.onNodeWithText("Auto-Lock").performScrollTo().performClick()
        compose.onNodeWithText("Immediately").performClick()
        assertEquals(null, chosen)
        compose.onNodeWithText("Enter your current password to set Auto-Lock to Immediately.").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("the password")
        compose.onNodeWithText("Change").performClick()
        compose.waitForIdle()

        assertEquals(AutoLock.IMMEDIATELY, chosen)
    }

    /** A change of Auto-Lock is kept only when the App Password is typed in the step that its sheet's choice opens. */
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

    /** Cancel during the password check closes the dialog, and the check that finishes after it must not apply the choice. */
    @Test
    fun cancellingTheAutoLockStepDuringTheCheckDoesNotApplyTheChoice() {
        val gate = CompletableDeferred<Unit>()
        var chosen: AutoLock? = null
        compose.setContent {
            ClearAirHarness {
                SecuritySettingsPage(
                    lock = LockSettings(AutoLock.AFTER_ONE_MINUTE),
                    biometric = BiometricSwitch(on = false, canEnable = true),
                    onEntryGesture = {},
                    onChangePassword = {},
                    duressSet = false,
                    onDuress = {},
                    onBiometric = {},
                    checkPassword = { withContext(NonCancellable) { gate.await(); PasswordCheck.Accepted } },
                    onAutoLock = { chosen = it },
                    offline = null, onLock = {}, onBack = {},
                )
            }
        }

        compose.onNodeWithText("Auto-Lock").performScrollTo().performClick()
        compose.onNodeWithText("Immediately").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("the password")
        compose.onNodeWithText("Change").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Enter your current password to set Auto-Lock to Immediately.").assertDoesNotExist()
        gate.complete(Unit)
        compose.waitForIdle()

        assertEquals(null, chosen)
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
