package app.lumio.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.app.AddressBookEntry
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.home.NativeAsset
import app.lumio.home.TokenAsset
import app.lumio.portfolio.ChainFamily
import app.lumio.portfolio.PortfolioFilter
import app.lumio.portfolio.PortfolioHolding
import app.lumio.portfolio.holdingOf
import app.lumio.transactions.ActivityFilter
import app.lumio.transactions.TxFeedItem
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.kit.ChoiceGroup
import app.lumio.ui.kit.ChoiceRow
import app.lumio.ui.kit.ClearAirChoiceSheet
import app.lumio.ui.portfolio.PortfolioScreen
import app.lumio.ui.transactions.ActivityList
import app.lumio.ui.transactions.AddOnLenslyScreen
import app.lumio.ui.transactions.AddressInfoSheet
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
    fun portfolioBalances() = compose.lumioSnapshot("portfolio_balances") { PortfolioSample(PortfolioFilter()) }

    @Test
    fun portfolioNotGroupedByChain() = compose.lumioSnapshot("portfolio_chain_filtered") { PortfolioSample(PortfolioFilter(chain = ChainFamily.BTC)) }

    @Test
    fun portfolioEmptyOnMainnet() = compose.lumioSnapshot("portfolio_empty") { PortfolioEmpty() }

    @Test
    fun chainChoices() = compose.lumioSnapshot("portfolio_chain_sheet") { ChainChoices() }

    @Test
    fun groupingDisabledForOneChain() = compose.lumioSnapshot("portfolio_grouping_sheet") { GroupingChoices() }

    @Test
    fun activityList() = compose.lumioSnapshot("activity_list") { ActivitySample() }

    @Test
    fun addressKnown() = compose.lumioSnapshot("address_known") { AddressSheetSample(known = true) }

    @Test
    fun addressUnknown() = compose.lumioSnapshot("address_unknown") { AddressSheetSample(known = false) }

    @Test
    fun addOnLensly() = compose.lumioSnapshot("address_handoff") { HandoffSample() }
}

/** The network of the sample balances: Ethereum mainnet, with its layer 2s, and Bitcoin. */
internal val SampleEthereum = NetworkId(Networks.evm(1), testnet = false)
internal val SampleArbitrum = NetworkId(Networks.evm(42161), testnet = false)
internal val SampleBase = NetworkId(Networks.evm(8453), testnet = false)
internal val SampleBitcoin = NetworkId(Networks.BTC_MAINNET, testnet = false)

/** The sample holdings: ETH on three networks, Bitcoin, USDC on two, and WETH on Arbitrum. */
internal fun sampleHoldings(): List<PortfolioHolding> = listOf(
    holdingOf(NativeAsset("e1", "ETH", "Ethereum", SampleEthereum, BigDecimal("12"), usd = 51_000.0, change24h = -2.41)),
    holdingOf(NativeAsset("e2", "ETH", "Arbitrum One", SampleArbitrum, BigDecimal("5"), usd = 21_250.0, change24h = -2.41)),
    holdingOf(NativeAsset("e3", "ETH", "Base", SampleBase, BigDecimal("5.23"), usd = 22_221.11, change24h = -2.41)),
    holdingOf(NativeAsset("b1", "BTC", "Bitcoin", SampleBitcoin, BigDecimal("0.2104"), usd = 22_811.64, change24h = 1.82)),
    holdingOf(sampleToken(SampleEthereum, "USDC", "USD Coin", "10000", 10_000.0)),
    holdingOf(sampleToken(SampleBase, "USDC", "USD Coin", "10100", 10_100.0)),
    holdingOf(sampleToken(SampleArbitrum, "WETH", "Wrapped Ether", "0.85", 3_642.85, change = -2.41)),
)

private fun sampleToken(network: NetworkId, symbol: String, name: String, amount: String, usd: Double, change: Double? = 0.0): TokenAsset {
    val contract = "0x" + symbol.lowercase().padEnd(40, '0')
    return TokenAsset(
        id = TokenAsset.idOf(network, contract),
        symbol = symbol,
        name = network.name,
        network = network,
        contract = contract,
        balance = BigDecimal(amount),
        usd = usd,
        change24h = change,
    )
}

/** The Portfolio tab with the sample balances under [filter]. */
@Composable
internal fun PortfolioSample(filter: PortfolioFilter) {
    PortfolioScreen(
        holdings = sampleHoldings(),
        filter = filter,
        onFilter = {},
        walletName = "Air 1",
        accounts = mapOf(ChainFamily.EVM to 3, ChainFamily.BTC to 2),
        showBalance = true,
        testnet = false,
        recent = sampleActivity().take(2),
        names = sampleNames(),
        zone = ZoneOffset.UTC,
        onOpenActivity = {},
        onOpenRow = {},
        chainIconsOf = { row -> row.networks.map { AssetIconSpec.Chain(null) } },
    )
}

@Composable
internal fun PortfolioEmpty() {
    PortfolioScreen(
        holdings = emptyList(),
        filter = PortfolioFilter(),
        onFilter = {},
        walletName = "Air 1",
        accounts = emptyMap(),
        showBalance = true,
        testnet = false,
        recent = emptyList(),
        names = emptyMap(),
        zone = ZoneOffset.UTC,
        onOpenActivity = {},
        onOpenRow = {},
    )
}

@Composable
internal fun ChainChoices() {
    ClearAirChoiceSheet(
        title = "Chains",
        groups = listOf(
            ChoiceGroup(
                null,
                listOf(
                    ChoiceRow("All Chains", {}, detail = "Grouped by asset", trailing = "\$141,025.60", selected = true),
                    ChoiceRow("Ethereum", {}, detail = "Ethereum and its L2s", trailing = "\$94,471.11"),
                    ChoiceRow("Bitcoin", {}, detail = "2 accounts", trailing = "\$22,811.64"),
                ),
            ),
        ),
    )
}

@Composable
internal fun GroupingChoices() {
    ClearAirChoiceSheet(
        title = "Grouping",
        groups = listOf(
            ChoiceGroup(
                null,
                listOf(
                    ChoiceRow("Grouped by Asset", {}, detail = "Grouping needs all chains", enabled = false),
                    ChoiceRow("Not Grouped", {}, selected = true),
                ),
            ),
        ),
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
