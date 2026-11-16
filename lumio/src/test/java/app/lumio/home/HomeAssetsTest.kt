package app.lumio.home

import app.lumio.data.asset.AssetId
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
