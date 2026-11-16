package app.lumio.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.theme.LenslyTheme
import app.lumio.btc.EsploraServers
import app.lumio.data.config.DisplaySettings
import app.lumio.evm.EvmSettings
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
        DisplaySettingsPage(settings = DisplaySettings(), notice = null, onShowTotal = {}, onShowAssets = {}, onMinimumUsd = {}, onBack = {})
    }

    @Test
    fun theTransactionsPageHasNoSaveAndItsSwitchReportsAtOnce() {
        var reported: Boolean? = null
        compose.setContent {
            LenslyTheme {
                TransactionsSettingsPage(
                    hideSpam = true,
                    notice = null,
                    onHideSpam = { reported = it },
                    onBack = {},
                    comments = {},
                )
            }
        }

        compose.onAllNodesWithText("Save", ignoreCase = true).assertCountEquals(0)
        compose.onNodeWithText("Hide spam and scam transactions").performClick()
        assertEquals(false, reported)
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
    fun theAddressBookPageHasNoSave() = assertNoSave {
        AddressBookSettingsPage(onBack = {}, wallets = { Text("No wallets paired yet.") })
    }

    @Test
    fun theSecurityPageHasNoSaveAndOpensTheGestureChoice() {
        var opened = false
        compose.setContent {
            LenslyTheme {
                SecuritySettingsPage(entryGesture = EntryGesture.DEFAULT, onEntryGesture = { opened = true }, onBack = {})
            }
        }

        compose.onAllNodesWithText("Save", ignoreCase = true).assertCountEquals(0)
        compose.onNodeWithText("Hidden entry gesture").performClick()
        assertEquals(true, opened)
    }

    @Test
    fun theAboutPageHasNoSave() = assertNoSave {
        AboutSettingsPage(version = "0.1.0", onLicences = {}, onBack = {})
    }

    @Test
    fun theDeveloperPageHasNoSave() = assertNoSave {
        DeveloperSettingsPage(onBack = {}, section = { Text("Demo portfolio") })
    }
}
