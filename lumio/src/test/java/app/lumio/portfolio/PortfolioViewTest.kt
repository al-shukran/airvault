package app.lumio.portfolio

import app.lumio.data.config.Fiat
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.home.NativeAsset
import app.lumio.home.TokenAsset
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortfolioViewTest {
    private val ethereum = NetworkId(Networks.evm(1), testnet = false)
    private val arbitrum = NetworkId(Networks.evm(42161), testnet = false)
    private val base = NetworkId(Networks.evm(8453), testnet = false)
    private val bitcoin = NetworkId(Networks.BTC_MAINNET, testnet = false)

    private fun native(network: NetworkId, symbol: String, amount: String, usd: Double?, change: Double? = null) =
        holdingOf(NativeAsset(id = NativeAsset.idOf(network), symbol = symbol, name = "x", network = network, balance = BigDecimal(amount), usd = usd, change24h = change))

    private fun token(
        network: NetworkId,
        symbol: String,
        name: String,
        amount: String,
        usd: Double?,
        known: Boolean = true,
        custom: Boolean = false,
    ): PortfolioHolding {
        val contract = "0x" + symbol.lowercase().padEnd(40, '0')
        val asset = TokenAsset(
            id = TokenAsset.idOf(network, contract),
            symbol = symbol,
            name = "x",
            network = network,
            contract = contract,
            balance = BigDecimal(amount),
            usd = usd,
            known = known,
            custom = custom,
        )
        return holdingOf(asset, title = name)
    }

    @Test
    fun nativeCoinOnTwoNetworksAddsUpIntoOneGroupedRow() {
        val rows = PortfolioView.rows(listOf(native(ethereum, "ETH", "1", 3000.0), native(arbitrum, "ETH", "0.5", 1500.0)), PortfolioFilter())

        assertEquals(1, rows.size)
        assertEquals("Ethereum", rows[0].title)
        assertEquals(0, BigDecimal("1.5").compareTo(rows[0].amount))
        assertEquals(4500.0, rows[0].usd!!, 1e-9)
        assertEquals(2, rows[0].networks.size)
        assertEquals(true, rows[0].merged)
    }

    @Test
    fun knownOfficialTokensAddUpButLookalikesAndCustomTokensNever() {
        val holdings = listOf(
            token(ethereum, "USDC", "USD Coin", "10", 10.0),
            token(base, "USDC", "USD Coin", "20", 20.0),
            token(arbitrum, "USDC.e", "Bridged USDC", "5", 5.0),
            token(base, "USDC", "USD Coin", "7", 7.0, custom = true),
        )

        val rows = PortfolioView.rows(holdings, PortfolioFilter(ascending = true))

        assertEquals(3, rows.size)
        val merged = rows.single { it.networks.size == 2 }
        assertEquals("USD Coin", merged.title)
        assertEquals(0, BigDecimal("30").compareTo(merged.amount))
        assertEquals(true, rows.single { it.coin == "USDC.e" }.bridged)
    }

    @Test
    fun wrappedTokenNeverMergesWithTheNativeCoin() {
        val rows = PortfolioView.rows(
            listOf(native(arbitrum, "ETH", "0.1", 300.0), token(arbitrum, "WETH", "Wrapped Ether", "0.85", 2550.0)),
            PortfolioFilter(),
        )

        assertEquals(2, rows.size)
        assertEquals(false, rows.any { it.merged })
    }

    @Test
    fun notGroupedKeepsEveryHoldingOnItsOwnRow() {
        val holdings = listOf(native(ethereum, "ETH", "1", 3000.0), native(arbitrum, "ETH", "0.5", 1500.0))

        val rows = PortfolioView.rows(holdings, PortfolioFilter(grouped = false))

        assertEquals(2, rows.size)
        assertEquals(true, rows.none { it.merged })
    }

    @Test
    fun choosingOneChainTurnsGroupingOffAndHidesTheOthers() {
        val holdings = listOf(native(ethereum, "ETH", "1", 3000.0), native(arbitrum, "ETH", "0.5", 1500.0), native(bitcoin, "BTC", "0.2", 9000.0))

        val rows = PortfolioView.rows(holdings, PortfolioFilter(chain = ChainFamily.EVM))

        assertEquals(listOf("Ethereum", "Ethereum"), rows.map { it.title })
        assertEquals(false, PortfolioFilter(chain = ChainFamily.EVM).isGrouped)
    }

    @Test
    fun balanceSortPutsLargestFirstAndUnpricedLast() {
        val holdings = listOf(
            native(ethereum, "ETH", "1", 3000.0),
            native(bitcoin, "BTC", "0.2", 9000.0),
            native(arbitrum, "ETH", "2", null),
        )

        val largest = PortfolioView.rows(holdings, PortfolioFilter(grouped = false)).map { it.coin }
        val smallest = PortfolioView.rows(holdings, PortfolioFilter(grouped = false, ascending = true)).map { it.coin }

        assertEquals(listOf("BTC", "ETH", "ETH"), largest)
        assertEquals(listOf("ETH", "BTC", "ETH"), smallest)
    }

    @Test
    fun nameSortIsAtoZOrReversed() {
        val holdings = listOf(native(bitcoin, "BTC", "1", 1.0), native(ethereum, "ETH", "1", 1.0))

        val ascending = PortfolioView.rows(holdings, PortfolioFilter(sort = BalanceSort.NAME, ascending = true)).map { it.title }
        val descending = PortfolioView.rows(holdings, PortfolioFilter(sort = BalanceSort.NAME, ascending = false)).map { it.title }

        assertEquals(listOf("Bitcoin", "Ethereum"), ascending)
        assertEquals(listOf("Ethereum", "Bitcoin"), descending)
    }

    @Test
    fun totalCountsTheChangeOnlyWhereThereIsAPriceAndAChange() {
        // 1000 now after a 10% rise: 909.09 before, so the change is 90.91 (10%).
        val total = PortfolioView.total(listOf(native(ethereum, "ETH", "1", 1000.0, change = 10.0), native(bitcoin, "BTC", "1", null)))

        assertEquals(1000.0, total.usd!!, 1e-9)
        assertEquals(90.909, total.changeUsd!!, 1e-3)
        assertEquals(10.0, total.changePct!!, 1e-6)
    }

    @Test
    fun allocationShowsTheThreeLargestCoinsAndTheRestAsOther() {
        val holdings = listOf(
            native(ethereum, "ETH", "1", 66.0),
            native(bitcoin, "BTC", "1", 15.0),
            token(base, "USDC", "USD Coin", "15", 15.0),
            native(arbitrum, "ARB", "1", 2.0),
            native(arbitrum, "POL", "1", 2.0),
        )

        val slices = PortfolioView.allocation(holdings)

        assertEquals(listOf("ETH", "BTC", "USDC", "Other"), slices.map { it.label })
        assertEquals(0.66, slices[0].share, 1e-9)
        assertEquals(0.04, slices[3].share, 1e-9)
        assertEquals(emptyList<AllocationSlice>(), PortfolioView.allocation(listOf(native(ethereum, "ETH", "1", null))))
    }

    @Test
    fun sentenceNamesEachChoiceAndTheAggregationRule() {
        assertEquals("Showing all chains, grouped by asset, largest balance first.", PortfolioFilter().sentence().plainText())
        assertEquals("Showing all chains, not grouped, largest balance first.", PortfolioFilter(grouped = false).sentence().plainText())
        assertEquals("Showing all chains, grouped by asset, sorted by name, A to Z.", PortfolioFilter(sort = BalanceSort.NAME, ascending = true).sentence().plainText())
        assertEquals("Showing Bitcoin only (grouping is off for a single chain).", PortfolioFilter(chain = ChainFamily.BTC).sentence().plainText())
    }

    @Test
    fun coinsAndChangesReadTheSameInEveryLocale() {
        assertEquals("20,100", PortfolioFormat.coins(BigDecimal("20100")))
        assertEquals("22.23", PortfolioFormat.coins(BigDecimal("22.23000")))
        assertEquals("▼ 1.15%", PortfolioFormat.change(-1.15))
        assertEquals("−\$1,728.13", PortfolioFormat.signedAmount(-1728.13, Fiat.USD))
        assertEquals("0.00%", PortfolioFormat.change(0.0))
        assertNull(PortfolioView.total(emptyList()).usd)
    }

    @Test
    fun fundsWithNoPriceAreNotEmptyButZeroBalancesAre() {
        assertEquals(true, PortfolioView.isEmpty(emptyList()))
        assertEquals(true, PortfolioView.isEmpty(listOf(native(ethereum, "ETH", "0", null))))
        assertEquals(false, PortfolioView.isEmpty(listOf(native(ethereum, "ETH", "15", null))))
        assertEquals(false, PortfolioView.isEmpty(listOf(native(ethereum, "ETH", "0", 0.0))))
    }
}
