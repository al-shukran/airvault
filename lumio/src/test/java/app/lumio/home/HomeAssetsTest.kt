package app.lumio.home

import app.lumio.data.config.AssetSort
import app.lumio.data.config.AssetSortKey
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.price.Price
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The rules of Home's asset list: the minimum, the order, the total, the prices and the masks. */
class HomeAssetsTest {
    private val ethereum = NetworkId(Family.EVM, "1", testnet = false)
    private val sepolia = NetworkId(Family.EVM, "11155111", testnet = true)
    private val bitcoin = NetworkId(Family.BTC, "mainnet", testnet = false)

    private fun asset(
        id: String,
        name: String = id,
        network: NetworkId = ethereum,
        balance: String = "1",
        usd: Double? = null,
        change: Double? = null,
    ) = NativeAsset(
        id = id,
        symbol = id,
        name = name,
        network = network,
        balance = BigDecimal(balance),
        usd = usd,
        change24h = change,
    )

    private fun ids(assets: List<HomeAsset>): List<String> = assets.map { it.id }

    @Test
    fun visibleKeepsTheAssetsAtOrAboveTheMinimumAndNeverHidesAnUnpricedOne() {
        val assets = listOf(
            asset("big", usd = 5.0),
            asset("edge", usd = 1.0),
            asset("dust", usd = 0.5),
            asset("testnet", network = sepolia),
        )

        assertEquals(listOf("big", "edge", "testnet"), ids(HomeAssets.visible(assets, minimumUsd = 1.0)))
    }

    @Test
    fun sortedByValueDescendingPutsTheUnpricedAssetsLast() {
        val assets = listOf(
            asset("b", usd = 2.0),
            asset("unpriced", network = sepolia),
            asset("a", usd = 9.0),
            asset("c", usd = 2.0),
        )

        val sorted = HomeAssets.sorted(assets, AssetSort(AssetSortKey.USD_VALUE, descending = true))

        assertEquals(listOf("a", "b", "c", "unpriced"), ids(sorted))
    }

    @Test
    fun sortedByValueAscendingStillPutsTheUnpricedAssetsLast() {
        val assets = listOf(
            asset("b", usd = 2.0),
            asset("unpriced", network = sepolia),
            asset("a", usd = 9.0),
            asset("c", usd = 2.0),
        )

        val sorted = HomeAssets.sorted(assets, AssetSort(AssetSortKey.USD_VALUE, descending = false))

        assertEquals(listOf("b", "c", "a", "unpriced"), ids(sorted))
    }

    @Test
    fun sortedByNameIgnoresCase() {
        val assets = listOf(asset("z", name = "zcash"), asset("a", name = "Arbitrum"), asset("m", name = "bitcoin"))

        assertEquals(listOf("a", "m", "z"), ids(HomeAssets.sorted(assets, AssetSort(AssetSortKey.NAME, descending = false))))
        assertEquals(listOf("z", "m", "a"), ids(HomeAssets.sorted(assets, AssetSort(AssetSortKey.NAME, descending = true))))
    }

    @Test
    fun sortedByBalanceCompareTheAmountsNotTheirText() {
        val assets = listOf(asset("ten", balance = "10"), asset("nine", balance = "9.5"), asset("quarter", balance = "0.25"))

        assertEquals(listOf("ten", "nine", "quarter"), ids(HomeAssets.sorted(assets, AssetSort(AssetSortKey.BALANCE, descending = true))))
    }

    @Test
    fun sortedByChangePutsTheAssetsWithoutAChangeLastInEitherDirection() {
        val assets = listOf(
            asset("up", change = 1.5),
            asset("none"),
            asset("down", change = -2.0),
        )

        assertEquals(listOf("up", "down", "none"), ids(HomeAssets.sorted(assets, AssetSort(AssetSortKey.CHANGE_24H, descending = true))))
        assertEquals(listOf("down", "up", "none"), ids(HomeAssets.sorted(assets, AssetSort(AssetSortKey.CHANGE_24H, descending = false))))
    }

    @Test
    fun theTotalIsTheSumOfThePricedAssets() {
        val assets = listOf(asset("a", usd = 2.5), asset("b", usd = 1.5), asset("c"))

        assertEquals(4.0, HomeAssets.totalUsd(assets)!!, 0.0001)
    }

    @Test
    fun theTotalIsUnknownWhenNoAssetHasAPrice() {
        assertNull(HomeAssets.totalUsd(listOf(asset("testnet", network = sepolia))))
        assertNull(HomeAssets.totalUsd(emptyList()))
    }

    @Test
    fun pricingAppliesTheNetworkPriceToTheBalanceAndLeavesATestnetUnpriced() {
        val balances = listOf(
            asset("eth", network = ethereum, balance = "2"),
            asset("sepolia", network = sepolia, balance = "3"),
        )
        val prices = mapOf(ethereum to Price(usd = 3000.0, change24hPct = -1.5, asOfMs = 10L, stale = false))

        val priced = HomeAssets.priced(balances, prices)

        assertEquals(6000.0, priced[0].usd!!, 0.0001)
        assertEquals(-1.5, priced[0].change24h!!, 0.0001)
        assertNull(priced[1].usd)
        assertNull(priced[1].change24h)
    }

    @Test
    fun theStalePriceDateIsTheOldestKeptPriceOfTheListedAssets() {
        val prices = mapOf(
            ethereum to Price(usd = 3000.0, change24hPct = null, asOfMs = 500L, stale = true),
            bitcoin to Price(usd = 90_000.0, change24hPct = null, asOfMs = 100L, stale = true),
        )
        val listed = listOf(asset("eth", network = ethereum))

        assertEquals(500L, HomeAssets.staleSinceMs(listed, prices))
        assertNull(HomeAssets.staleSinceMs(listed, mapOf(ethereum to Price(3000.0, null, 500L, stale = false))))
    }

    @Test
    fun aMaskedAmountShowsTheSameMaskWhateverTheAmount() {
        assertEquals(Masking.MASK, Masking.amount("\$1,234.56", hidden = true))
        assertEquals(Masking.MASK, Masking.amount("0", hidden = true))
        assertEquals("\$1,234.56", Masking.amount("\$1,234.56", hidden = false))
        assertFalse(Masking.MASK.any { it.isDigit() })
    }

    @Test
    fun homeNumbersReadTheSameInEveryLocale() {
        assertEquals("\$1,234.56", HomeFormat.usd(1234.56))
        assertEquals("0.5", HomeFormat.coins(BigDecimal("0.50000")))
        assertEquals("0", HomeFormat.coins(BigDecimal("0.000")))
        assertEquals("+1.25%", HomeFormat.change(1.25))
        assertEquals("-0.40%", HomeFormat.change(-0.4))
    }
}
