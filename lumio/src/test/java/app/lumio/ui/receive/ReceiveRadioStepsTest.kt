package app.lumio.ui.receive

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.receive.ReceiveAccount
import app.lumio.receive.ReceiveAnswers
import app.lumio.receive.ReceiveCoin
import app.lumio.receive.ReceiveNetwork
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.kit.ADDRESS_LINE_TAG
import app.lumio.ui.kit.spokenAddress
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The network and account steps of a Receive: each choice is a radio row that names what it is, and a tap picks it and goes on to the next step. The
 * network's short name is its title, and the rest of its name is the line under it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiveRadioStepsTest {
    @get:Rule
    val compose = createComposeRule()

    private val pulsar = ReceiveAccount(name = "PulsarNova 5", label = "Trading", group = "DeFi", address = "0x1020304050607080901122b")
    private val felix = ReceiveAccount(name = "FelixStone 0", label = "Savings", group = "Core", address = "0x1020304050607080901127a")
    private val ethereum = ReceiveNetwork(name = "Ethereum", icon = AssetIconSpec.Letter("E"), accounts = listOf(pulsar))
    private val base = ReceiveNetwork(
        name = "Base L2 on Ethereum",
        icon = AssetIconSpec.Letter("B"),
        accounts = listOf(pulsar, felix),
        label = "Base",
    )
    private val usdc = ReceiveCoin(
        symbol = "USDC",
        name = "USD Coin",
        subtitle = "",
        held = true,
        icon = AssetIconSpec.Letter("U"),
        networks = listOf(ethereum, base),
        networkNote = "Bridged USDC, such as USDC.e, is a different asset and isn't listed here.",
    )

    private fun show() {
        compose.setContent {
            ClearAirTheme {
                ReceiveFlowSheet(
                    visible = true,
                    opening = 0,
                    start = ReceiveAnswers(coin = usdc),
                    assets = emptyList(),
                    onClose = {},
                )
            }
        }
    }

    @Test
    fun theNetworkStepNamesEachNetworkWithWhatItIs() {
        show()

        compose.onNodeWithText("On Which Network?").assertIsDisplayed()
        compose.onNodeWithText("Ethereum").assertIsDisplayed()
        compose.onNodeWithText("Base").assertIsDisplayed()
        compose.onNodeWithText("L2 on Ethereum").assertIsDisplayed()
        compose.onNodeWithText("Bridged USDC, such as USDC.e", substring = true).assertIsDisplayed()
    }

    @Test
    fun pickingANetworkGoesOnToTheAccountStep() {
        show()

        compose.onNodeWithText("Base").performClick()

        compose.onNodeWithText("Into Which Account?").assertIsDisplayed()
        compose.onNodeWithText("PulsarNova 5").assertIsDisplayed()
        compose.onNodeWithText("Trading · Group DeFi", substring = true).assertIsDisplayed()
    }

    @Test
    fun anAccountRowHoldsItsLabelAndGroupAndItsAddressOnALineOfItsOwn() {
        show()

        compose.onNodeWithText("Base").performClick()

        compose.onNodeWithText("Trading · Group DeFi").assertIsDisplayed()
        val spoken = compose.onAllNodesWithTag(ADDRESS_LINE_TAG, useUnmergedTree = true)[0].fetchSemanticsNode().config[SemanticsProperties.Text].first().text
        assertEquals(spokenAddress(pulsar.address).text, spoken)
        compose.onAllNodesWithText("0x", substring = true, useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun anAccountWithNeitherLabelNorGroupHasItsAddressWhereTheDetailWouldBe() {
        val plain = ReceiveAccount(name = "Account 3", label = null, group = null, address = "0x1020304050607080901127a1020304050607080")
        val network = ReceiveNetwork(name = "Ethereum", icon = AssetIconSpec.Letter("E"), accounts = listOf(plain, felix))
        compose.setContent {
            ClearAirTheme {
                ReceiveFlowSheet(
                    visible = true,
                    opening = 0,
                    start = ReceiveAnswers(coin = usdc.copy(networks = listOf(network)), network = network),
                    assets = emptyList(),
                    onClose = {},
                )
            }
        }

        val title = compose.onNodeWithText("Account 3", useUnmergedTree = true).getBoundsInRoot()
        val address = compose.onAllNodesWithTag(ADDRESS_LINE_TAG, useUnmergedTree = true)[0].getBoundsInRoot()
        assertTrue(address.top >= title.bottom && address.top - title.bottom < 6.dp, "the address sits right under the title: title $title, address $address")
    }
}
