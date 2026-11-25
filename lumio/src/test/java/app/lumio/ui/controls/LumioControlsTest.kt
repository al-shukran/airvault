package app.lumio.ui.controls

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettings
import app.lumio.network.NetworkMode
import app.lumio.ui.kit.ClearAirSegmented
import app.lumio.ui.network.label
import app.lumio.ui.settings.EvmSettingsSection
import app.lumio.ui.settings.ProviderPage
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
            ClearAirTheme {
                ProviderPage(
                    name = "mempool.space",
                    status = "✓ Working",
                    statusNote = null,
                    enabled = true,
                    onEnabled = {},
                    tags = emptyList(),
                    networks = emptyList(),
                    keyed = false,
                    keyRequired = false,
                    savedTail = null,
                    offline = null,
                    onSaveKey = {},
                    onBack = {},
                    onLock = {},
                )
            }
        }

        compose.onNodeWithTag("provider-enabled").assert(hasRole(Role.Switch))
    }

    @Test
    fun aChainIsAToggleSwitchAndAddingAChainIsARow() {
        compose.setContent {
            ClearAirTheme {
                EvmSettingsSection(
                    settings = EvmSettings(enabled = setOf(1L)),
                    chains = listOf(EvmChain(1L, "Ethereum", "ETH", 18, false, null)),
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

        compose.onAllNodes(hasRole(Role.Switch)).assertCountEquals(1)
        compose.onNodeWithText("Add Custom Network", ignoreCase = true).assertIsEnabled()
    }

    @Test
    fun theAddressStepperStopsAtItsLimits() {
        compose.setContent {
            ClearAirTheme {
                EvmSettingsSection(
                    settings = EvmSettings(addressCount = EvmSettings.MAX_ADDRESS_COUNT),
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

        compose.onNodeWithText("+").assertIsNotEnabled()
        compose.onNodeWithText("\u2212").assertIsEnabled()
    }

    private fun hasRole(role: Role): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

}
