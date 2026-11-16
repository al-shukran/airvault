package app.lumio.demo

import app.lumio.data.asset.AssetId
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.price.ChartRange
import app.lumio.data.tx.TxAssetRef
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxQuery
import app.lumio.data.tx.TxSort
import app.lumio.data.tx.TxStatus
import app.lumio.home.TokenAsset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** The demo portfolio's fixed data: its holdings, prices and charts, and the ledger that the store's queries read. Debug builds only. */
class DemoPortfolioTest {
    private val now = System.currentTimeMillis()
    private val ethereum = NetworkId(Family.EVM, "1", false)
    private val base = NetworkId(Family.EVM, "8453", false)
    private val monad = NetworkId(Family.EVM, "10143", true)
    private val sepolia = NetworkId(Family.EVM, "11155111", true)
    private val bitcoin = NetworkId(Family.BTC, "mainnet", false)
    private val bitcoinTestnet4 = NetworkId(Family.BTC, "testnet4", true)
    private val mainnetNetworks = setOf(ethereum, bitcoin)
    private val testnetNetworks = setOf(monad, bitcoinTestnet4, sepolia)

    @Test
    fun mainnetHoldsBitcoinEthereumBaseAndTheStablecoins() {
        val mainnet = DemoAssets.holdings.filter { !it.testnet }
        assertEquals(setOf("BTC", "ETH", "USDC", "USDT"), mainnet.map { it.symbol }.toSet())
        assertEquals(setOf(bitcoin, ethereum, base), mainnet.map { it.network }.toSet())
        assertTrue(mainnet.any { it is TokenAsset && it.network == base && it.symbol == "USDC" })
    }

    @Test
    fun testnetHoldsMonadTestnet4BitcoinAndSepolia() {
        val testnet = DemoAssets.holdings.filter { it.testnet }
        assertEquals(setOf(monad, bitcoinTestnet4, sepolia), testnet.map { it.network }.toSet())
        assertEquals("MON", testnet.single { it.network == monad }.symbol)
        assertTrue(testnet.all { it.balance.signum() > 0 })
    }

    @Test
    fun onlyMainnetCoinsArePriced() {
        for (asset in DemoAssets.holdings) {
            assertEquals(!asset.testnet, DemoAssets.price(asset.assetId, now) != null, asset.id)
        }
    }

    @Test
    fun aPriceCarriesItsChangeAndTheTimeAsked() {
        val price = DemoAssets.price(AssetId.Native(bitcoin), now)!!
        assertEquals(1.82, price.change24hPct)
        assertEquals(now, price.asOfMs)
        assertEquals(false, price.stale)
    }

    @Test
    fun everyChartHasItsRangesPointsAndEndsOnThePrice() {
        for (range in ChartRange.entries) {
            val series = DemoAssets.series(AssetId.Native(ethereum), range, now)!!
            assertEquals(range.points, series.points.size, range.label)
            assertEquals(3_412.5, series.points.last().usd, range.label)
            assertEquals(now, series.points.last().timeMs, range.label)
            assertTrue(series.points.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs }, range.label)
        }
    }

    @Test
    fun aTestnetCoinHasNoPriceAndAnEmptyChart() {
        assertNull(DemoAssets.price(AssetId.Native(monad), now))
        assertTrue(DemoAssets.series(AssetId.Native(monad), ChartRange.D7, now)!!.points.isEmpty())
    }

    @Test
    fun theLedgerHasSentReceivedSelfAndSwapRowsOverMixedDates() {
        val rows = DemoLedger.rows
        val byHash = rows.groupBy { it.hash }
        assertEquals(18, rows.size)
        assertEquals(16, byHash.size)
        assertTrue(rows.any { it.direction == TxDirection.IN })
        assertTrue(rows.any { it.direction == TxDirection.OUT })
        assertTrue(rows.any { it.direction == TxDirection.SELF })
        val swaps = byHash.values.filter { it.size == 2 }
        assertEquals(2, swaps.size)
        for (swap in swaps) {
            assertEquals(setOf(TxDirection.IN, TxDirection.OUT), swap.map { it.direction }.toSet())
            assertTrue(swap.all { !it.network.testnet })
        }
        assertEquals(1, rows.count { it.status == TxStatus.PENDING })
        assertEquals(1, rows.count { it.status == TxStatus.FAILED })
        val times = rows.map { it.timestampMs }
        assertTrue(times.max() - times.min() > 7 * DAY_MS, "the rows span more than a week")
        assertEquals(rows.size, rows.map { it.id }.toSet().size, "every id is distinct")
        assertTrue(rows.all { it.hash.matches(Regex("(0x)?[0-9a-f]{64}")) })
        assertTrue(rows.all { it.amount.signum() >= 0 })
    }

    @Test
    fun aSideQueryShowsOnlyItsOwnRows() {
        val mainnet = DemoLedger.matching(DemoLedger.rows, TxQuery(networks = mainnetNetworks))
        val testnet = DemoLedger.matching(DemoLedger.rows, TxQuery(networks = testnetNetworks))
        assertEquals(13, mainnet.size)
        assertEquals(5, testnet.size)
        assertTrue(mainnet.none { it.network.testnet })
        assertTrue(testnet.all { it.network.testnet })
    }

    @Test
    fun anAssetQueryFindsTheRowsOfItsCoinOrToken() {
        val usdc = DemoLedger.matching(DemoLedger.rows, TxQuery(assets = setOf(TxAssetRef(ethereum, DemoAssets.USDC_ETHEREUM))))
        assertEquals(2, usdc.size)
        assertTrue(usdc.all { it.asset.symbol == "USDC" && it.network == ethereum })
        val ether = DemoLedger.matching(DemoLedger.rows, TxQuery(assets = setOf(TxAssetRef(ethereum, null))))
        assertTrue(ether.isNotEmpty() && ether.all { it.asset.contract == null && it.network == ethereum })
    }

    @Test
    fun aSearchReadsTheSymbolOfEveryUsdcRow() {
        val found = DemoLedger.matching(DemoLedger.rows, TxQuery(text = "usdc"))
        assertEquals(4, found.size)
        assertTrue(found.all { it.asset.symbol == "USDC" })
    }

    @Test
    fun aNewestFirstQueryIsInTimeOrder() {
        val rows = DemoLedger.matching(DemoLedger.rows, TxQuery(networks = mainnetNetworks, sort = TxSort.TIMESTAMP, descending = true))
        assertEquals(rows.map { it.timestampMs }.sortedDescending(), rows.map { it.timestampMs })
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
