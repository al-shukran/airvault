package app.lumio.ui.controls

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun aSegmentIsThirtySevenDpTallAndPressesInFortyEight() {
        var chosen = NetworkMode.MAINNET
        compose.setContent {
            ClearAirTheme {
                ClearAirSegmented(options = NetworkMode.entries, selected = chosen, label = { it.label() }, onSelect = { chosen = it })
            }
        }

        val segment = compose.onNodeWithText("Testnet", ignoreCase = true)
        segment.assertHeightIsEqualTo(37.dp)
        // 5 dp above the 37 dp segment is inside the 48 dp touch target (5.5 dp on each side) and outside the segment.
        segment.performTouchInput { click(Offset(center.x, -5.dp.toPx())) }
        assertEquals(NetworkMode.TESTNET, chosen)
    }

    /** At the largest font scale the label's line is twice as tall, so the segment grows with it instead of cutting the label off. */
    @Test
    fun aSegmentGrowsWithTheFontSoTheLabelIsNotClipped() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                ClearAirTheme {
                    ClearAirSegmented(options = NetworkMode.entries, selected = NetworkMode.MAINNET, label = { it.label() }, onSelect = {})
                }
            }
        }

        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Testnet", ignoreCase = true, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        val label = results.single()
        val segment = compose.onNodeWithText("Testnet", ignoreCase = true).fetchSemanticsNode().size.height
        assertFalse("the label is cut off by its segment", label.didOverflowHeight)
        assertTrue("the segment ($segment px) is shorter than its label's line (${label.multiParagraph.height} px)", segment >= label.multiParagraph.height)
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
