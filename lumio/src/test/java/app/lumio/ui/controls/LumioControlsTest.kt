package app.lumio.ui.controls

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LumenTheme
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettings
import app.lumio.network.NetworkMode
import app.lumio.ui.kit.ClearAirSegmented
import app.lumio.ui.network.label
import app.lumio.ui.settings.DataProvidersScreen
import app.lumio.ui.settings.EvmSettingsSection
import app.lumio.ui.settings.ProviderRow
import app.lumio.ui.settings.ProviderStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The control rules of Lumio's screens: the side choice is one segmented control, every on-off setting is a switch, a status is text
 * rather than a button's label, and the stepper stops at its limits.
 */
@RunWith(RobolectricTestRunner::class)
class LumioControlsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theSideChoiceIsOneRadioGroupNotTwoButtons() {
        compose.setContent {
            ClearAirTheme {
                ClearAirSegmented(options = NetworkMode.entries, selected = NetworkMode.MAINNET, label = { it.label() }, onSelect = {})
            }
        }

        compose.onNodeWithText("Mainnet", ignoreCase = true).assert(hasRole(Role.RadioButton))
        compose.onNodeWithText("Testnet", ignoreCase = true).assert(hasRole(Role.RadioButton))
    }

    @Test
    fun aProviderSwitchIsAToggleSwitch() {
        compose.setContent {
            LumenTheme {
                DataProvidersScreen(
                    data = listOf(provider("mempool")),
                    rpc = emptyList(),
                    onBack = {},
                    onEnabled = { _, _ -> },
                    onMove = { _, _ -> },
                    onApiKey = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("enable-mempool").assert(hasRole(Role.Switch))
    }

    @Test
    fun aChainIsAToggleSwitchAndAddingAChainIsARow() {
        compose.setContent {
            LumenTheme {
                EvmSettingsSection(
                    settings = EvmSettings(enabled = setOf(1L)),
                    chains = listOf(EvmChain(1L, "Ethereum", "ETH", 18, false, null)),
                    busy = false,
                    notice = null,
                    onAddressCount = {},
                    onEnabled = { _, _ -> },
                    onSetRpc = { _, _, _ -> },
                    onResetRpc = {},
                    onRemoveCustom = {},
                    onAddCustom = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("Ethereum").assert(hasRole(Role.Switch))
        compose.onNodeWithText("Add Custom Chain", ignoreCase = true).assertIsEnabled()
    }

    @Test
    fun theAddressStepperStopsAtItsLimits() {
        compose.setContent {
            LumenTheme {
                EvmSettingsSection(
                    settings = EvmSettings(addressCount = EvmSettings.MAX_ADDRESS_COUNT),
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
            }
        }

        compose.onNodeWithContentDescription("Show more addresses").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Show fewer addresses").assertIsEnabled()
    }

    private fun hasRole(role: Role): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun provider(id: String) = ProviderRow(
        id = id,
        mevProtected = false,
        acceptsKey = false,
        enabled = true,
        apiKey = null,
        status = ProviderStatus.Ready,
        canMoveUp = true,
        canMoveDown = true,
    )
}
