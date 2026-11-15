package app.lumio.ui.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.lensly.ui.theme.LenslyTheme
import app.lumio.evm.EvmSettings
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Lumio's settings: one screen with a Bitcoin section and an Ethereum section. */
@RunWith(RobolectricTestRunner::class)
class LumioSettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theBitcoinAndEthereumSectionsRenderOnOneScreen() {
        compose.setContent {
            LenslyTheme {
                LumioSettingsScreen(
                    onBack = {},
                    bitcoin = {
                        BitcoinServersSettings(
                            mainnet = "https://mempool.space/api",
                            testnet4 = "https://mempool.space/testnet4/api",
                            error = null,
                            onSave = { _, _ -> },
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
                    onDataProviders = {},
                    onThirdPartyLicences = {},
                )
            }
        }

        compose.onNodeWithText("Bitcoin", ignoreCase = true).assertExists()
        compose.onNodeWithText("Ethereum", ignoreCase = true).assertExists()
        compose.onNodeWithText("Mainnet").assertExists()
        compose.onNodeWithText("Save", ignoreCase = true).assertExists()
        compose.onNodeWithText("Reset to defaults", ignoreCase = true).assertExists()
        compose.onNodeWithText("Addresses per wallet", ignoreCase = true).assertExists()
        compose.onNodeWithText("Chains", ignoreCase = true).assertExists()
        compose.onNodeWithText("Add custom chain", ignoreCase = true).assertExists()
        compose.onNodeWithText("Data providers", ignoreCase = true).assertExists()
        compose.onNodeWithText("Third-party licences", ignoreCase = true).assertExists()
    }

    @Test
    fun theThirdPartyLicencesRowOpensTheLicences() {
        var opened = false
        compose.setContent {
            LenslyTheme {
                LumioSettingsScreen(
                    onBack = {},
                    bitcoin = {},
                    ethereum = {},
                    onDataProviders = {},
                    onThirdPartyLicences = { opened = true },
                )
            }
        }

        compose.onNodeWithText("Third-party licences", ignoreCase = true).performScrollTo().performClick()
        assertTrue(opened)
    }

    @Test
    fun theEvmSettingsButtonIsGone() {
        compose.setContent {
            LenslyTheme {
                LumioSettingsScreen(
                    onBack = {},
                    bitcoin = {},
                    ethereum = {},
                    onDataProviders = {},
                    onThirdPartyLicences = {},
                )
            }
        }

        compose.onNodeWithText("EVM settings", ignoreCase = true).assertDoesNotExist()
    }
}
