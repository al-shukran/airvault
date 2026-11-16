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
import app.lensly.ui.theme.LenslyTheme
import app.lumio.btc.EsploraServers
import app.lumio.data.config.DisplaySettings
import app.lumio.data.config.LockSettings
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
        compose.setContent { LenslyTheme { page() } }
        compose.onAllNodesWithText("Save", ignoreCase = true).assertCountEquals(0)
    }

    @Test
    fun theDisplayPageHasNoSave() = assertNoSave {
        DisplaySettingsPage(settings = DisplaySettings(), notice = null, onShowTotal = {}, onShowAssets = {}, onBack = {})
    }

    @Test
    fun theNetworksPageHasNoSave() = assertNoSave {
        NetworksSettingsPage(
            onBack = {},
            bitcoin = {
                BitcoinServersSettings(
                    mainnet = EsploraServers.MAINNET_DEFAULT,
                    testnet4 = EsploraServers.TESTNET4_DEFAULT,
                    error = null,
                    onApply = { _, _ -> },
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
        WalletsSettingsPage(onBack = {}, wallets = { Text("Pair from Lensly") })
    }

    @Test
    fun theAddressBookPageHasNoSave() = assertNoSave {
        AddressBookSettingsPage(onBack = {}, wallets = { Text("No wallets paired yet.") })
    }

    @Test
    fun theAddressBookPageHasNoPairingOrDeletionButOpensEachBook() {
        var opened: String? = null
        val wallet = PairedWallet(id = "w1", name = "Demo", fingerprint = 1L, createdAtMs = 0L, evmAccounts = emptyList(), bitcoin = emptyList())
        compose.setContent {
            LenslyTheme {
                AddressBookSettingsPage(
                    onBack = {},
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
            LenslyTheme {
                SecuritySettingsPage(
                    entryGesture = EntryGesture.DEFAULT,
                    lock = LockSettings(),
                    onEntryGesture = { opened = true },
                    onChangePassword = {},
                    onIdleMinutes = {},
                    onLockWhenSwitching = {},
                    onBack = {},
                )
            }
        }

        compose.onAllNodesWithText("Save", ignoreCase = true).assertCountEquals(0)
        compose.onNodeWithText("Hidden entry gesture").performClick()
        assertEquals(true, opened)
    }

    @Test
    fun theSecurityPageOpensTheAppPasswordChange() {
        var opened = false
        compose.setContent {
            LenslyTheme {
                SecuritySettingsPage(
                    entryGesture = EntryGesture.DEFAULT,
                    lock = LockSettings(),
                    onEntryGesture = {},
                    onChangePassword = { opened = true },
                    onIdleMinutes = {},
                    onLockWhenSwitching = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Change password").performScrollTo().performClick()
        assertEquals(true, opened)
    }

    @Test
    fun theSecurityLockSwitchAppliesAtOnceAndTheTimeoutShowsItsValue() {
        var switching: Boolean? = null
        compose.setContent {
            LenslyTheme {
                SecuritySettingsPage(
                    entryGesture = EntryGesture.DEFAULT,
                    lock = LockSettings(),
                    onEntryGesture = {},
                    onChangePassword = {},
                    onIdleMinutes = {},
                    onLockWhenSwitching = { switching = it },
                    onBack = {},
                )
            }
        }

        // The page scrolls: the lock section sits below the fold of the test's small window, so each control is scrolled to first.
        compose.onNodeWithText("30 minutes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Default").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Lock when switching apps").performScrollTo().performClick()
        assertEquals(true, switching)
    }

    @Test
    fun theAboutPageHasNoSave() = assertNoSave {
        AboutSettingsPage(version = "0.1.0", onLicences = {}, onBack = {})
    }

    @Test
    fun theTransactionsPageHasNoSave() = assertNoSave {
        TransactionsSettingsPage(onBack = {}, comments = {})
    }
}
