package app.lumio.ui.controls

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettings
import app.lumio.network.NetworkMode
import app.lumio.ui.kit.ClearAirSegmented
import app.lumio.ui.network.label
import app.lumio.ui.settings.EvmSettingsSection
import app.lumio.ui.settings.ProviderPage
import org.junit.Assert.assertEquals
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
    fun aSegmentIsThirtySixDpTallAndPressesInFortyEight() {
        var chosen = NetworkMode.MAINNET
        compose.setContent {
            ClearAirTheme {
                ClearAirSegmented(options = NetworkMode.entries, selected = chosen, label = { it.label() }, onSelect = { chosen = it })
            }
        }

        val segment = compose.onNodeWithText("Testnet", ignoreCase = true)
        segment.assertHeightIsEqualTo(36.dp)
        // 5 dp above the 36 dp segment is inside the 48 dp touch target (6 dp on each side) and outside the segment.
        segment.performTouchInput { click(Offset(center.x, -5.dp.toPx())) }
        assertEquals(NetworkMode.TESTNET, chosen)
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
