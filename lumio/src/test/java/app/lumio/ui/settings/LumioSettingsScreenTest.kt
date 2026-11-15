package app.lumio.ui.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.lensly.ui.theme.LenslyTheme
import app.lumio.evm.EvmSettings
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
                )
            }
        }

        compose.onNodeWithText("Bitcoin").assertExists()
        compose.onNodeWithText("Ethereum").assertExists()
        compose.onNodeWithText("Mainnet").assertExists()
        compose.onNodeWithText("Save", ignoreCase = true).assertExists()
        compose.onNodeWithText("Reset to defaults", ignoreCase = true).assertExists()
        compose.onNodeWithText("Addresses per wallet").assertExists()
        compose.onNodeWithText("Chains").assertExists()
        compose.onNodeWithText("Add custom chain", ignoreCase = true).assertExists()
        compose.onNodeWithText("Data providers", ignoreCase = true).assertExists()
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
                )
            }
        }

        compose.onNodeWithText("EVM settings", ignoreCase = true).assertDoesNotExist()
    }
}
