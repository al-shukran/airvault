package app.lumio.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.theme.LumenTheme
import app.lumio.btc.EsploraServers
import app.lumio.data.config.DisplaySettings
import app.lumio.data.config.LockSettings
import app.lumio.data.config.TransactionSettings
import app.lumio.evm.EvmSettings
import app.lumio.pair.PairedWallet
import kotlin.test.assertEquals
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
            settings = DisplaySettings(), notice = null, onShowTotal = {}, onShowAssets = {}, onCurrency = {}, onSmallBalance = {},
            offline = null, onLock = {}, onBack = {},
        )
    }

    @Test
    fun theNetworksPageHasNoSave() = assertNoSave {
        NetworksSettingsPage(
            offline = null, onLock = {}, onBack = {},
            bitcoin = {
                BitcoinServersSettings(
                    mainnet = EsploraServers.MAINNET_DEFAULT,
                    testnet4 = EsploraServers.TESTNET4_DEFAULT,
                    indexer = "",
                    error = null,
                    onApply = { _, _ -> },
                    onIndexer = {},
                    onReset = {},
                )
            },
            ethereum = {
                EvmSettingsSection(
                    settings = EvmSettings(),
                    chains = emptyList(),
                    busy = false,
                    notice = null,
                    onAddressCount = {},
                    onEnabled = { _, _ -> },
                    onSetRpc = { _, _, _ -> },
                    onResetRpc = {},
                    onRemoveCustom = {},
                    onAddCustom = { _, _ -> },
                )
            },
        )
    }

    @Test
    fun theWalletsPageHasNoSave() = assertNoSave {
        WalletsSettingsPage(offline = null, onLock = {}, onBack = {}, wallets = { Text("Pair from Lensly") })
    }

    @Test
    fun theAddressBookPageHasNoSave() = assertNoSave {
        AddressBookSettingsPage(offline = null, onLock = {}, onBack = {}, wallets = { Text("No wallets paired yet.") })
    }

    @Test
    fun theAddressBookPageHasNoPairingOrDeletionButOpensEachBook() {
        var opened: String? = null
        val wallet = PairedWallet(id = "w1", name = "Demo", fingerprint = 1L, createdAtMs = 0L, evmAccounts = emptyList(), bitcoin = emptyList())
        compose.setContent {
            ClearAirHarness {
                AddressBookSettingsPage(
                    offline = null, onLock = {}, onBack = {},
                    wallets = { AddressBookRows(wallets = listOf(wallet), onAddressBook = { opened = it.id }) },
                )
            }
        }

        compose.onAllNodesWithText("Pair from Lensly").assertCountEquals(0)
        compose.onAllNodesWithText("Delete", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Demo").performClick()
        assertEquals("w1", opened)
    }

    @Test
    fun theSecurityPageHasNoSaveAndOpensTheGestureChoice() {
        var opened = false
        compose.setContent {
            ClearAirHarness {
                SecuritySettingsPage(
                    entryGesture = EntryGesture.DEFAULT,
                    lock = LockSettings(),
                    deviceProtectionOn = false,
                    onEntryGesture = { opened = true },
                    onChangePassword = {},
                    onDeviceProtection = {},
                    onIdleMinutes = {},
                    onLockWhenSwitching = {},
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
                    entryGesture = EntryGesture.DEFAULT,
                    lock = LockSettings(),
                    deviceProtectionOn = false,
                    onEntryGesture = {},
                    onChangePassword = { opened = true },
                    onDeviceProtection = {},
                    onIdleMinutes = {},
                    onLockWhenSwitching = {},
                    offline = null, onLock = {}, onBack = {},
                )
            }
        }

        compose.onNodeWithText("Change Password").performScrollTo().performClick()
        assertEquals(true, opened)
    }

    @Test
    fun theSecurityRowOpensDeviceProtectionAndTheSwitchIsNotOnSecurity() {
        var opened = false
        compose.setContent {
            ClearAirHarness {
                SecuritySettingsPage(
                    entryGesture = EntryGesture.DEFAULT,
                    lock = LockSettings(),
                    deviceProtectionOn = false,
                    onEntryGesture = {},
                    onChangePassword = {},
                    onDeviceProtection = { opened = true },
                    onIdleMinutes = {},
                    onLockWhenSwitching = {},
                    offline = null, onLock = {}, onBack = {},
                )
            }
        }

        compose.onNodeWithText("Biometric Unlock").assertDoesNotExist()
        compose.onNodeWithText("Device Protection").performScrollTo().performClick()
        assertEquals(true, opened)
    }

    @Test
    fun theDeviceProtectionSwitchReportsTheOwnersChoice() {
        var changed: Boolean? = null
        compose.setContent {
            ClearAirHarness {
                DeviceProtectionSettingsPage(
                    biometric = BiometricSwitch(on = false, canEnable = true),
                    onBiometric = { changed = it },
                    offline = null, onLock = {}, onBack = {},
                )
            }
        }

        compose.onNodeWithText("Biometric Unlock").performScrollTo().performClick()
        assertEquals(true, changed)
    }

    @Test
    fun theDeviceProtectionSwitchSaysWhyItCannotBeTurnedOn() {
        compose.setContent {
            ClearAirHarness {
                DeviceProtectionSettingsPage(
                    biometric = BiometricSwitch(on = false, canEnable = false),
                    onBiometric = {},
                    offline = null, onLock = {}, onBack = {},
                )
            }
        }

        compose.onNodeWithText(BIOMETRIC_UNAVAILABLE_REASON).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theSecurityLockSwitchAppliesAtOnceAndTheTimeoutShowsItsValue() {
        var switching: Boolean? = null
        compose.setContent {
            ClearAirHarness {
                SecuritySettingsPage(
                    entryGesture = EntryGesture.DEFAULT,
                    lock = LockSettings(),
                    deviceProtectionOn = false,
                    onEntryGesture = {},
                    onChangePassword = {},
                    onDeviceProtection = {},
                    onIdleMinutes = {},
                    onLockWhenSwitching = { switching = it },
                    offline = null, onLock = {}, onBack = {},
                )
            }
        }

        // The page scrolls: the lock section sits below the fold of the test's small window, so each control is scrolled to first.
        compose.onNodeWithText("30 minutes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Lock When Switching Apps").performScrollTo().performClick()
        assertEquals(true, switching)
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
    fun theHideSpamSwitchReportsTheOwnersChoiceAndTheTitleShowsThePause() {
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

        // The section title is an eyebrow, so it is drawn in capitals.
        compose.onNodeWithText("LAST CHANCE BEFORE SENDING · 5 S").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Hide Spam").performScrollTo().performClick()
        assertEquals(false, hide)
    }
}

/** The theme a settings page is drawn in in the app: the Clear Air theme, under the Lumen theme that wraps Lumio. */
@androidx.compose.runtime.Composable
private fun ClearAirHarness(content: @androidx.compose.runtime.Composable () -> Unit) {
    LumenTheme { app.lensly.ui.theme.ClearAirTheme(content = content) }
}
