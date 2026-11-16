package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.screenshot.snapshot
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxAssetRef
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxFilter
import app.lumio.data.tx.TxPeriod
import app.lumio.data.tx.TxSort
import app.lumio.data.tx.TxStatus
import app.lumio.transactions.TxAssetOption
import app.lumio.transactions.TxDetail
import app.lumio.transactions.TxFeedItem
import app.lumio.ui.transactions.TransactionDetailScreen
import app.lumio.ui.transactions.TransactionsScreen
import app.lumio.ui.transactions.TxFilterContent
import java.math.BigDecimal
import java.math.BigInteger
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of the Transactions tab: the feed filled from the store, its empty state, the filter sheet, and the detail of one transaction, in the Lensly
 * dark theme. Times are shown in UTC so that the goldens read the same on every host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransactionsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val ethereum = NetworkId(Family.EVM, "1", testnet = false)
    private val sepolia = NetworkId(Family.EVM, "11155111", testnet = true)
    private val bitcoin = NetworkId(Family.BTC, "mainnet", testnet = false)
    private val me = "0x" + "a1".repeat(20)
    private val friend = "0x" + "5c".repeat(20)
    private val spammer = "0x" + "77".repeat(20)
    private val usdcContract = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    private val ether = TxAsset(contract = null, symbol = "ETH", name = "Ether", decimals = 18)
    private val usdc = TxAsset(contract = usdcContract, symbol = "USDC", name = "USD Coin", decimals = 6)
    private val bitcoinAsset = TxAsset(contract = null, symbol = "BTC", name = "Bitcoin", decimals = 8)
    private val oct = 1_698_000_000_000L
    private val nov = 1_700_000_000_000L

    private fun hashOf(n: Int): String = "0x" + n.toString(16).padStart(2, '0').repeat(32)

    private fun tx(
        n: Int,
        network: NetworkId,
        asset: TxAsset,
        amount: BigInteger,
        direction: TxDirection,
        counterparty: String?,
        timestampMs: Long,
        status: TxStatus = TxStatus.CONFIRMED,
        memo: String? = null,
    ) = LumioTx(
        id = "EVM:${network.chain}:${hashOf(n)}",
        network = network,
        account = me,
        hash = hashOf(n),
        timestampMs = timestampMs,
        asset = asset,
        amount = amount,
        direction = direction,
        fee = null,
        counterparty = counterparty,
        status = status,
        confirmations = if (status == TxStatus.PENDING) 0L else 120L,
        inputData = null,
        memo = memo,
    )

    private fun eth(whole: String): BigInteger = BigDecimal(whole).movePointRight(18).toBigIntegerExact()

    private fun usdcUnits(whole: String): BigInteger = BigDecimal(whole).movePointRight(6).toBigIntegerExact()

    private val feed = listOf(
        TxFeedItem(
            tx = tx(1, ethereum, ether, eth("1.5"), TxDirection.IN, friend, nov),
            usd = 3450.0,
            comment = "Rent split for October",
            chain = "Ethereum",
        ),
        TxFeedItem(
            tx = tx(2, sepolia, ether, eth("0.25"), TxDirection.OUT, friend, nov - 86_400_000L),
            usd = null,
            chain = "Sepolia",
        ),
        TxFeedItem(
            tx = tx(3, ethereum, usdc, usdcUnits("10"), TxDirection.SELF, null, nov - 2 * 86_400_000L),
            usd = 10.0,
            chain = "Ethereum",
        ),
        TxFeedItem(
            tx = tx(4, ethereum, usdc, usdcUnits("120"), TxDirection.IN, friend, nov - 3 * 86_400_000L, status = TxStatus.PENDING),
            usd = 120.0,
            comment = "Invoice 42",
            chain = "Ethereum",
        ),
        TxFeedItem(
            tx = tx(5, bitcoin, bitcoinAsset, BigInteger.valueOf(100_000L), TxDirection.OUT, "bc1qexamplepaymentaddress", oct),
            usd = 95.0,
            chain = "Bitcoin",
        ),
        TxFeedItem(
            tx = tx(6, ethereum, ether, eth("0.5"), TxDirection.IN, friend, oct - 86_400_000L),
            usd = 1150.0,
            chain = "Ethereum",
        ),
    )

    private val options = listOf(
        TxAssetOption(TxAssetRef(ethereum, null), "ETH", "Ethereum"),
        TxAssetOption(TxAssetRef(ethereum, usdcContract), "USDC", "Ethereum"),
        TxAssetOption(TxAssetRef(sepolia, null), "ETH", "Sepolia"),
        TxAssetOption(TxAssetRef(bitcoin, null), "BTC", "Bitcoin"),
    )

    @Test
    fun transactionsFeed() = compose.snapshot("transactions_feed") {
        TransactionsScreen(
            rows = feed,
            count = feed.size,
            filter = TxFilter(),
            assets = options,
            refreshing = false,
            onFilter = {},
            onMore = {},
            onOpen = {},
            onRefresh = {},
            zone = ZoneOffset.UTC,
        )
    }

    @Test
    fun transactionsEmpty() = compose.snapshot("transactions_empty") {
        TransactionsScreen(
            rows = emptyList(),
            count = 0,
            filter = TxFilter(),
            assets = options,
            refreshing = false,
            onFilter = {},
            onMore = {},
            onOpen = {},
            onRefresh = {},
            zone = ZoneOffset.UTC,
        )
    }

    @Test
    fun transactionsFilterSheet() = compose.snapshot("transactions_filter_sheet") {
        TxFilterContent(
            filter = TxFilter(
                period = TxPeriod.LAST_7D,
                assets = setOf(TxAssetRef(ethereum, usdcContract)),
                directions = setOf(TxDirection.IN),
                amountAbove = BigDecimal("10"),
                sort = TxSort.AMOUNT,
                descending = true,
            ),
            assets = options,
            onFilter = {},
            onDone = {},
            zone = ZoneOffset.UTC,
        )
    }

    @Test
    fun transactionDetail() = compose.snapshot("transaction_detail") {
        TransactionDetailScreen(
            detail = TxDetail(
                tx = feed[0].tx,
                usd = 3450.0,
                comment = "Rent split for October",
                chain = "Ethereum",
                feeText = "0.00042 ETH",
                explorerUrl = "https://etherscan.io/tx/" + hashOf(1),
                flags = emptyList(),
            ),
            onBack = {},
            onExplorer = {},
            onSaveComment = {},
            zone = ZoneOffset.UTC,
        )
    }

    @Test
    fun transactionDetailFlaggedAsSpam() = compose.snapshot("transaction_detail_flagged") {
        val dust = tx(7, ethereum, TxAsset("0x" + "ab".repeat(20), "FREE", "Free claim token", 18), BigInteger.ONE, TxDirection.IN, spammer, nov)
        TransactionDetailScreen(
            detail = TxDetail(
                tx = dust,
                usd = null,
                comment = null,
                chain = "Ethereum",
                feeText = null,
                explorerUrl = null,
                flags = listOf("Moves no value", "Its name or symbol points to a link or a call to action"),
            ),
            onBack = {},
            onExplorer = {},
            onSaveComment = {},
            zone = ZoneOffset.UTC,
        )
    }
}
