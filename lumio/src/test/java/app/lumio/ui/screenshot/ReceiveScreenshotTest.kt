package app.lumio.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import app.lumio.receive.ReceiveAccount
import app.lumio.receive.ReceiveAnswers
import app.lumio.receive.ReceiveCoin
import app.lumio.receive.ReceiveNetwork
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.receive.ReceiveFlowSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of the Receive sheet: the code of one address (the last step), the asset step of a global Receive, and its network and account steps,
 * reached by picking USD Coin and then Base. Each is a dark golden, and a light one with `_light` in its name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiveScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun receiveCode() = compose.lumioSnapshot("receive_code") { ReceiveSheetScene(start = monStart()) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun receiveCodeLight() = compose.lumioSnapshot("receive_code_light") { ReceiveSheetScene(start = monStart()) }

    @Test
    fun receiveChooseAsset() = compose.lumioSnapshot("receive_choose_asset") { ReceiveSheetScene(assets = assets()) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun receiveChooseAssetLight() = compose.lumioSnapshot("receive_choose_asset_light") { ReceiveSheetScene(assets = assets()) }

    @Test
    fun receiveChooseNetwork() = compose.lumioSnapshot(
        "receive_choose_network",
        beforeCapture = { pick("USD Coin") },
    ) { ReceiveSheetScene(assets = assets()) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun receiveChooseNetworkLight() = compose.lumioSnapshot(
        "receive_choose_network_light",
        beforeCapture = { pick("USD Coin") },
    ) { ReceiveSheetScene(assets = assets()) }

    @Test
    fun receiveChooseAccount() = compose.lumioSnapshot(
        "receive_choose_account",
        beforeCapture = { pick("USD Coin", "Base") },
    ) { ReceiveSheetScene(assets = assets()) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun receiveChooseAccountLight() = compose.lumioSnapshot(
        "receive_choose_account_light",
        beforeCapture = { pick("USD Coin", "Base") },
    ) { ReceiveSheetScene(assets = assets()) }
}

/**
 * Taps each row named in [rows], in order, as a person would: each tap is one step of the Receive. The clock runs during a tap, because the press
 * of a row times its tap with the clock.
 */
private fun ComposeContentTestRule.pick(vararg rows: String) {
    mainClock.autoAdvance = true
    rows.forEach { row ->
        onNodeWithText(row).performTouchInput { click() }
        waitForIdle()
    }
}

/** The Receive sheet open over the page, from [start], with the coins [assets] of a global Receive. */
@Composable
private fun ReceiveSheetScene(start: ReceiveAnswers = ReceiveAnswers(), assets: List<ReceiveCoin> = emptyList()) {
    ReceiveFlowSheet(visible = true, opening = 0, start = start, assets = assets, onClose = {})
}

private val pulsar = ReceiveAccount(name = "PulsarNova 5", label = "Trading", group = "DeFi", address = ADDRESS)
private val felix = ReceiveAccount(name = "FelixStone 0", label = "Savings", group = "Core", address = FELIX)
private val nothing = ReceiveAccount(name = "NothingLess 1", label = null, group = null, address = NOTHING)

/** The Receive of MON into [pulsar] on Monad Testnet: the asset, the network and the account are known, as from an account page. */
private fun monStart(): ReceiveAnswers {
    val network = ReceiveNetwork(name = "Monad Testnet", icon = AssetIconSpec.Letter("M"), accounts = listOf(pulsar))
    val mon = ReceiveCoin(
        symbol = "MON",
        name = "Monad",
        subtitle = "",
        held = true,
        icon = AssetIconSpec.Letter("M"),
        networks = listOf(network),
    )
    return ReceiveAnswers(coin = mon, network = network, account = pulsar)
}

/** The coins of a global Receive, held ones first, as the asset step lists them. */
private fun assets(): List<ReceiveCoin> {
    val ethereumNetworks = listOf(
        ReceiveNetwork("Ethereum Layer 1", AssetIconSpec.Chain(null), listOf(pulsar, felix, nothing)),
        ReceiveNetwork("Base L2 on Ethereum", AssetIconSpec.Letter("B"), listOf(pulsar, felix, nothing), "Only accounts that can hold USDC on Base are listed.", label = "Base"),
        ReceiveNetwork("Arbitrum L2 on Ethereum", AssetIconSpec.Letter("A"), listOf(pulsar, felix, nothing)),
    )
    val usdcNetworks = listOf(
        ReceiveNetwork("Ethereum Layer 1", AssetIconSpec.Chain(null), listOf(pulsar, felix, nothing)),
        ReceiveNetwork("Base L2 on Ethereum", AssetIconSpec.Letter("B"), listOf(pulsar, felix, nothing), "Only accounts that can hold USDC on Base are listed.", label = "Base"),
        ReceiveNetwork("Arbitrum L2 on Ethereum", AssetIconSpec.Letter("A"), listOf(pulsar, felix, nothing)),
        ReceiveNetwork("Optimism L2 on Ethereum", AssetIconSpec.Letter("O"), listOf(pulsar, felix, nothing)),
        ReceiveNetwork("Solana SPL token", AssetIconSpec.Letter("S"), listOf(pulsar)),
        ReceiveNetwork("Algorand ASA 31566704", AssetIconSpec.Letter("A"), listOf(pulsar)),
    )
    val monad = ReceiveNetwork("Monad Testnet", AssetIconSpec.Letter("M"), listOf(pulsar))
    return listOf(
        ReceiveCoin("ETH", "Ethereum", "ETH · on 3 networks you use", true, AssetIconSpec.Chain(null), ethereumNetworks),
        ReceiveCoin(
            symbol = "USDC",
            name = "USD Coin",
            subtitle = "USDC · on 6 networks",
            held = true,
            icon = AssetIconSpec.Letter("U"),
            networks = usdcNetworks,
            networkNote = "Bridged USDC, such as USDC.e, is a different asset and isn't listed here.",
        ),
        ReceiveCoin("BTC", "Bitcoin", "BTC · 2 accounts", true, AssetIconSpec.Bitcoin, listOf(ReceiveNetwork("Bitcoin Testnet4", AssetIconSpec.Bitcoin, listOf(pulsar)))),
        ReceiveCoin("SOL", "Solana", "SOL · 1 account", true, AssetIconSpec.Letter("S"), listOf(ReceiveNetwork("Solana Devnet", AssetIconSpec.Letter("S"), listOf(pulsar)))),
        ReceiveCoin("ALGO", "Algorand", "ALGO · 17 accounts", false, AssetIconSpec.Letter("A"), listOf(ReceiveNetwork("Algorand Testnet", AssetIconSpec.Letter("A"), listOf(pulsar)))),
        ReceiveCoin("MON", "Monad", "MON · Ethereum App accounts", false, AssetIconSpec.Letter("M"), listOf(monad)),
    )
}

private const val ADDRESS = "0x102030405060708090112131415161718191122b"
private const val FELIX = "0x10203040506070809011213141516171819112aa"
private const val NOTHING = "0x10203040506070809011213141516171819112ab"
