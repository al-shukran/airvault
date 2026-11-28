package app.lumio.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.lensly.app.AddressBookEntry
import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.home.NativeAsset
import app.lumio.home.TokenAsset
import app.lumio.network.NetworkMode
import app.lumio.portfolio.ChainFamily
import app.lumio.portfolio.PortfolioFilter
import app.lumio.portfolio.PortfolioHolding
import app.lumio.portfolio.PortfolioRow
import app.lumio.portfolio.SentencePicker
import app.lumio.portfolio.holdingOf
import app.lumio.transactions.ActivityFilter
import app.lumio.transactions.TxFeedItem
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.home.tokenIcon
import app.lumio.ui.kit.LumioTab
import app.lumio.ui.kit.WalletEmblemTone
import app.lumio.ui.network.PanelWallet
import app.lumio.ui.portfolio.OtherSide
import app.lumio.ui.portfolio.PortfolioScreen
import app.lumio.ui.portfolio.PortfolioSheet
import app.lumio.ui.shell.LumioRootScreen
import app.lumio.ui.transactions.ActivityList
import app.lumio.ui.transactions.AddOnLenslyScreen
import app.lumio.ui.transactions.AddressInfoSheet
import app.lumio.ui.wallets.AssetRows
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of the consolidated views in the dark theme: the Portfolio balances, the filter pickers, the activity, the address sheets and the handoff
 * page. The light theme is in [ConsolidatedViewsLightScreenshotTest], with the same screens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConsolidatedViewsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun portfolioBalances() = compose.portfolioFrame("portfolio_balances") { PortfolioSample(PortfolioFilter()) }

    /** Bitcoin's price is from earlier today and USDC's from another day, so both rows show their time. */
    @Test
    fun portfolioStalePrices() = compose.portfolioFrame("portfolio_stale_prices") {
        PortfolioSample(PortfolioFilter(), sampleStaleHoldings(), nowMs = SAMPLE_NOW_MS)
    }

    @Test
    fun portfolioNotGroupedByChain() = compose.portfolioFrame("portfolio_chain_filtered") { PortfolioSample(PortfolioFilter(chain = ChainFamily.BTC)) }

    @Test
    fun portfolioEmptyOnMainnet() = compose.portfolioFrame("portfolio_empty") { PortfolioEmpty() }

    /** The chain sheet open over the whole Portfolio, top bar and tab bar included: as tall as its rows, at the bottom, each row led by its logo. */
    @Test
    fun chainChoices() = compose.portfolioFrame("portfolio_chain_sheet", sheet = SentencePicker.CHAIN) { PortfolioSample(PortfolioFilter()) }

    /** The grouping sheet over the Portfolio of Solana alone, where grouping is disabled and says why. */
    @Test
    fun groupingDisabledForOneChain() = compose.portfolioFrame("portfolio_grouping_sheet", PortfolioFilter(chain = ChainFamily.SOL), SentencePicker.GROUPING) {
        PortfolioSample(PortfolioFilter(chain = ChainFamily.SOL))
    }

    @Test
    fun activityList() = compose.lumioSnapshot("activity_list") { ActivitySample() }

    @Test
    fun addressKnown() = compose.lumioSnapshot("address_known") { AddressSheetSample(known = true) }

    @Test
    fun addressUnknown() = compose.lumioSnapshot("address_unknown") { AddressSheetSample(known = false) }

    @Test
    fun addOnLensly() = compose.lumioSnapshot("address_handoff") { HandoffSample() }
}

/** The networks of the sample balances: Ethereum mainnet with two of its layer 2s, Bitcoin, Solana and Algorand. */
internal val SampleEthereum = NetworkId(Networks.evm(1), testnet = false)
internal val SampleArbitrum = NetworkId(Networks.evm(42161), testnet = false)
internal val SampleBase = NetworkId(Networks.evm(8453), testnet = false)
internal val SampleBitcoin = NetworkId(Networks.BTC_MAINNET, testnet = false)
private val SampleSolana = NetworkId(Networks.SOL_MAINNET, testnet = false)
private val SampleAlgorand = NetworkId(Networks.ALGO_MAINNET, testnet = false)

/**
 * The sample holdings, the approved frame's: ETH on three networks, Bitcoin in two accounts, USD Coin on two networks, Solana, WETH on Arbitrum, and two
 * balances the frame's total counts and its list leaves out, which the sample hides as small ([SAMPLE_SMALL]): bridged USDC on Arbitrum and Algorand.
 */
internal fun sampleHoldings(): List<PortfolioHolding> = listOf(
    holdingOf(NativeAsset("e1", "ETH", "Ethereum", SampleEthereum, BigDecimal("12"), usd = 51_000.0, change24h = -2.41)),
    holdingOf(NativeAsset("e2", "ETH", "Arbitrum One", SampleArbitrum, BigDecimal("5"), usd = 21_250.0, change24h = -2.41)),
    holdingOf(NativeAsset("e3", "ETH", "Base", SampleBase, BigDecimal("5.23"), usd = 23_021.11, change24h = -2.41)),
    holdingOf(NativeAsset("b1", "BTC", "Bitcoin", SampleBitcoin, BigDecimal("0.15"), usd = 16_262.86, change24h = 1.82)),
    holdingOf(NativeAsset("b2", "BTC", "Bitcoin", SampleBitcoin, BigDecimal("0.0604"), usd = 6_548.78, change24h = 1.82)),
    holdingOf(sampleToken(SampleEthereum, USDC_ETHEREUM, "USDC", "10000", 10_000.0), title = "USD Coin"),
    holdingOf(sampleToken(SampleBase, USDC_BASE, "USDC", "10100", 10_100.0), title = "USD Coin"),
    holdingOf(NativeAsset("s1", "SOL", "Solana", SampleSolana, BigDecimal("31.5"), usd = 5_802.30, change24h = 4.10)),
    holdingOf(sampleToken(SampleArbitrum, WETH_ARBITRUM, "WETH", "0.85", 3_642.85, change = -2.41), title = "WETH"),
    holdingOf(sampleToken(SampleArbitrum, USDC_E_ARBITRUM, "USDC", "1714.70", 1_714.70, change = null), title = "Bridged USDC"),
    holdingOf(NativeAsset("a1", "ALGO", "Algorand", SampleAlgorand, BigDecimal("1270"), usd = 426.50, change24h = 22.484)),
)

/** The sample's small balances, which its list hides: the two the frame's total counts and its list leaves out. */
private const val SAMPLE_SMALL = 2_000.0

/** The accounts of each chain, as the frame's chain sheet counts them. */
private val SAMPLE_ACCOUNTS = mapOf(ChainFamily.EVM to 3, ChainFamily.BTC to 2, ChainFamily.SOL to 1, ChainFamily.ALGO to 17)

private const val USDC_ETHEREUM = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
private const val USDC_BASE = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"
private const val USDC_E_ARBITRUM = "0xff970a61a04b1ca14834a43f5de4533ebddb5cc8"
private const val WETH_ARBITRUM = "0x82af49447d8a07e3bd95bd0d56f35241523fbab1"

private fun sampleToken(network: NetworkId, contract: String, symbol: String, amount: String, usd: Double, change: Double? = 0.0): TokenAsset = TokenAsset(
    id = TokenAsset.idOf(network, contract),
    symbol = symbol,
    name = network.name,
    network = network,
    contract = contract,
    balance = BigDecimal(amount),
    usd = usd,
    change24h = change,
)

private val SAMPLE_NOW_MS = Instant.parse("2026-11-26T15:30:00Z").toEpochMilli()

/**
 * The sample holdings with last-known prices: Bitcoin's is from 09:41 today and keeps its change, USDC's on Ethereum is from 25 November, and Wrapped
 * Ether's has no change, so its row shows the time alone.
 */
private fun sampleStaleHoldings(): List<PortfolioHolding> = sampleHoldings().map { holding ->
    when {
        holding.coin == "BTC" -> holding.copy(staleAsOfMs = Instant.parse("2026-11-26T09:41:00Z").toEpochMilli())
        holding.coin == "USDC" && holding.network == SampleEthereum -> holding.copy(staleAsOfMs = Instant.parse("2026-11-25T22:10:00Z").toEpochMilli())
        holding.coin == "WETH" -> holding.copy(change24h = null, staleAsOfMs = Instant.parse("2026-11-26T11:20:00Z").toEpochMilli())
        else -> holding
    }
}

/** The shared registry, read the way the registry tests read it, so the rows show the logos the app ships. */
private val registry: Registry = RegistryLoader.load { path ->
    listOf("../shared/src/main/assets", "../shared/src/debug/assets").map { File(it, path) }.firstOrNull(File::isFile)?.readText()
}

/** A row's logo, by the app's rule: a token's own, Ethereum's for ETH on several networks, and a native coin's chain logo. */
private fun sampleIcon(row: PortfolioRow): AssetIconSpec {
    val network = row.networks.first()
    val contract = row.contract
    if (contract != null) return tokenIcon(registry.token(Networks.evmIdOf(network.chain), contract), registry.network(Networks.evmIdOf(network.chain)), row.coin)
    if (row.merged && row.coin == "ETH") return AssetIconSpec.Chain(registry.network(1L))
    return AssetRows.nativeIcon(network, row.coin, registry::network)
}

/** The logo of a chain family in the chain sheet, as the app draws it. */
private fun sampleFamilyIcon(family: ChainFamily): AssetIconSpec = when (family) {
    ChainFamily.EVM -> AssetIconSpec.Chain(registry.network(1L))
    ChainFamily.BTC -> AssetIconSpec.Bitcoin
    ChainFamily.SOL -> AssetIconSpec.Solana
    ChainFamily.ALGO -> AssetIconSpec.Algorand
}

/** The mock's gesture area under the tab bar, which a phone's navigation inset gives it. */
private val GESTURE_AREA = 24.dp

/**
 * A golden of the Portfolio tab in the approved frame's 360 × 740 dp: the root top bar titled "Portfolio", [content], the tab bar, and the gesture
 * area. [sheet] opens that picker's sheet over the whole root, as the app's root draws a tab's sheet, for [filter].
 */
internal fun ComposeContentTestRule.portfolioFrame(
    name: String,
    filter: PortfolioFilter = PortfolioFilter(),
    sheet: SentencePicker? = null,
    content: @Composable ColumnScope.() -> Unit,
) = lumioSnapshot(name = name, frame = DpSize(360.dp, 740.dp)) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            LumioRootScreen(
                tab = LumioTab.PORTFOLIO,
                mode = NetworkMode.MAINNET,
                onMode = {},
                panelWallets = listOf(PanelWallet(id = "air-1", name = "Air 1", detail = "", tone = WalletEmblemTone.BLUE)),
                activeWalletId = "air-1",
                onWallet = {},
                testNetworks = 0,
                networkSentence = "",
                offline = null,
                onTab = {},
                onScan = {},
                onTestNetworks = {},
                onPair = {},
                onLock = {},
                search = { _ -> },
                content = content,
            )
        }
        Spacer(Modifier.height(GESTURE_AREA))
    }
    if (sheet != null) {
        PortfolioSheet(
            picker = sheet,
            onClose = {},
            holdings = sampleHoldings(),
            filter = filter,
            onFilter = {},
            accounts = SAMPLE_ACCOUNTS,
            showBalance = true,
            familyIcon = ::sampleFamilyIcon,
        )
    }
}

/** The Portfolio tab with the sample balances under [filter]. */
@Composable
internal fun PortfolioSample(filter: PortfolioFilter, holdings: List<PortfolioHolding> = sampleHoldings(), nowMs: Long = System.currentTimeMillis()) {
    PortfolioScreen(
        holdings = holdings,
        nowMs = nowMs,
        filter = filter,
        onPick = {},
        walletName = "Air 1",
        showBalance = true,
        hidesSmall = { usd -> usd != null && usd < SAMPLE_SMALL },
        testnet = false,
        zone = ZoneOffset.UTC,
        iconOf = ::sampleIcon,
        chainIconsOf = { row -> row.networks.map { AssetRows.nativeIcon(it, row.coin, registry::network) } },
        networkName = { network -> if (network == SampleArbitrum) "Arbitrum" else network.name },
    )
}

/** The empty mainnet of Air 1, whose testnet holds 12.5 MON, with the Receive capsule. */
@Composable
internal fun PortfolioEmpty() {
    PortfolioScreen(
        holdings = emptyList(),
        filter = PortfolioFilter(),
        onPick = {},
        walletName = "Air 1",
        showBalance = true,
        testnet = false,
        zone = ZoneOffset.UTC,
        otherSide = OtherSide(amount = "12.5 MON", network = "Monad Testnet", testnet = true, icon = AssetIconSpec.Chain(registry.network(10143L)), onSwitch = {}),
        onReceive = {},
    )
}

/** Sample activity: a receive from an address-book name, a pending contract call, and a send to an unnamed address. */
internal fun sampleActivity(): List<TxFeedItem> = listOf(
    TxFeedItem(tx = sampleTx("tx-in", SampleArbitrum, TxDirection.IN, "1000000000000000000", TxStatus.CONFIRMED, NOON_MS, "0x" + "9f".repeat(20)), usd = 4285.7, chain = "Arbitrum One"),
    TxFeedItem(tx = sampleTx("tx-call", SampleEthereum, TxDirection.OUT, "250000000000000000", TxStatus.PENDING, NOON_MS - HOUR_MS, "0x" + "3a".repeat(20), input = "0xa9059cbb"), usd = 571.2, chain = "Ethereum"),
    TxFeedItem(tx = sampleTx("tx-out", SampleBase, TxDirection.OUT, "5000000000", TxStatus.CONFIRMED, NOON_MS - 2 * DAY_MS, "0x" + "7e".repeat(20)), usd = 5000.0, chain = "Base"),
)

/** The names of the sample parties, by their address key. */
internal fun sampleNames(): Map<String, String> = mapOf(("0x" + "9f".repeat(20)) to "Exchange Deposit")

private const val HOUR_MS = 60 * 60 * 1000L
private const val DAY_MS = 24 * HOUR_MS
private val NOON_MS = Instant.parse("2026-11-21T12:00:00Z").toEpochMilli()

private fun sampleTx(
    id: String,
    network: NetworkId,
    direction: TxDirection,
    amountWei: String,
    status: TxStatus,
    timestampMs: Long,
    counterparty: String,
    input: String? = null,
): LumioTx = LumioTx(
    id = id,
    network = network,
    account = "0x" + "a1".repeat(20),
    hash = "0x" + id.hashCode().toUInt().toString(16).padStart(64, '0'),
    timestampMs = timestampMs,
    asset = if (network == SampleBase) {
        TxAsset(contract = "0x" + "ab".repeat(20), symbol = "USDC", name = "USD Coin", decimals = 6)
    } else {
        TxAsset(contract = null, symbol = "ETH", name = "Ether", decimals = 18)
    },
    amount = BigInteger(amountWei),
    direction = direction,
    fee = BigInteger.valueOf(42_000),
    counterparty = counterparty,
    status = status,
    confirmations = if (status == TxStatus.PENDING) 0L else 120L,
    inputData = input,
    memo = null,
)

/** The activity list, with three spam rows hidden and the rows grouped under their day. */
@Composable
internal fun ActivitySample() {
    ActivityList(
        items = sampleActivity(),
        filter = ActivityFilter(),
        onFilter = {},
        families = listOf(ChainFamily.EVM, ChainFamily.BTC),
        names = sampleNames(),
        spamHidden = 3,
        showingSpam = false,
        onShowSpam = {},
        showBalance = true,
        zone = ZoneOffset.UTC,
        nowMs = NOON_MS,
        onOpen = {},
    )
}

/** The address sheet of a known or an unknown address. */
@Composable
internal fun AddressSheetSample(known: Boolean) {
    val address = "0x" + "5c".repeat(20)
    AddressInfoSheet(
        address = address,
        entry = if (known) AddressBookEntry(id = "entry-1", address = address, name = "Lena Park", label = "Monad test buddy", notes = "Splits test MON") else null,
        walletName = "Air 1",
        seenIn = 2,
        onEdit = {},
        onAdd = {},
        onClose = {},
    )
}

/** The handoff page that shows an address as a code for Lensly. */
@Composable
internal fun HandoffSample() {
    AddOnLenslyScreen(
        address = "0x" + "7e21" + "5ac3".repeat(6) + "3c5e",
        edit = false,
        backLabel = "Transaction",
        onBack = {},
        onLock = {},
        onDone = {},
    )
}
