package app.lumio.home

import app.lumio.data.asset.AssetId
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
    private val usdc = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    private val dai = "0x6b175474e89094c44da98b954eedeac495271d0f"
    private val unlisted = "0x1111111111111111111111111111111111111111"

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

    private fun token(
        id: String,
        contract: String = usdc,
        network: NetworkId = ethereum,
        balance: String = "1",
        usd: Double? = null,
    ) = TokenAsset(
        id = id,
        symbol = id,
        name = "Ethereum",
        network = network,
        contract = contract,
        balance = BigDecimal(balance),
        usd = usd,
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
        val prices = mapOf<AssetId, Price>(AssetId.Native(ethereum) to Price(usd = 3000.0, change24hPct = -1.5, asOfMs = 10L, stale = false))

        val priced = HomeAssets.priced(balances, prices)

        assertEquals(6000.0, priced[0].usd!!, 0.0001)
        assertEquals(-1.5, priced[0].change24h!!, 0.0001)
        assertNull(priced[1].usd)
        assertNull(priced[1].change24h)
    }

    @Test
    fun aTokenIsPricedByItsContractAndNotByTheNativeCoinOfItsChain() {
        val prices = mapOf(
            AssetId.Native(ethereum) to Price(usd = 3000.0, change24hPct = 1.0, asOfMs = 10L, stale = false),
            AssetId.Erc20(ethereum, usdc) to Price(usd = 0.9998, change24hPct = -0.01, asOfMs = 10L, stale = false),
        )

        val priced = HomeAssets.priced(listOf(token("usdc", balance = "250"), token("dai", contract = dai, balance = "7")), prices)

        assertEquals(249.95, priced[0].usd!!, 0.0001)
        assertEquals(-0.01, priced[0].change24h!!, 0.0001)
        assertNull(priced[1].usd)
        assertNull(priced[1].change24h)
    }

    @Test
    fun theStalePriceDateIsTheOldestKeptPriceOfTheListedAssets() {
        val prices = mapOf(
            AssetId.Native(ethereum) to Price(usd = 3000.0, change24hPct = null, asOfMs = 500L, stale = true),
            AssetId.Native(bitcoin) to Price(usd = 90_000.0, change24hPct = null, asOfMs = 100L, stale = true),
            AssetId.Erc20(ethereum, usdc) to Price(usd = 1.0, change24hPct = null, asOfMs = 300L, stale = true),
        )
        val listed = listOf(asset("eth", network = ethereum), token("usdc"))

        assertEquals(300L, HomeAssets.staleSinceMs(listed, prices))
        assertNull(HomeAssets.staleSinceMs(listed, mapOf<AssetId, Price>(AssetId.Native(ethereum) to Price(3000.0, null, 500L, stale = false))))
    }

    @Test
    fun aPricedTokenSortsWithTheNativeCoinsAndAnUnpricedTokenIsNeverHidden() {
        val prices = mapOf(
            AssetId.Native(ethereum) to Price(usd = 500.0, change24hPct = 2.0, asOfMs = 1L, stale = false),
            AssetId.Erc20(ethereum, usdc) to Price(usd = 1.0, change24hPct = null, asOfMs = 1L, stale = false),
            AssetId.Erc20(ethereum, dai) to Price(usd = 2.0, change24hPct = null, asOfMs = 1L, stale = false),
        )
        val assets = HomeAssets.priced(
            listOf(
                asset("eth"),
                token("usdc", balance = "250"),
                token("crumbs", contract = dai, balance = "0.25"),
                token("unpriced", contract = unlisted, balance = "3"),
            ),
            prices,
        )

        val shown = HomeAssets.sorted(HomeAssets.visible(assets, minimumUsd = 1.0), AssetSort(AssetSortKey.USD_VALUE, descending = true))

        // The token at 250 USDC sorts beside the coin, the token worth 50 cents is hidden, and the token with no price stays, after the priced ones.
        assertEquals(listOf("eth", "usdc", "unpriced"), ids(shown))
        assertEquals(750.0, HomeAssets.totalUsd(shown)!!, 0.0001)
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
