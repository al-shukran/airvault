package app.lumio.ui.search

import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.CoinDirectory
import app.lumio.data.provider.DirectoryCoin
import app.lumio.data.provider.MarketCoin
import app.lumio.data.provider.MarketCoins
import app.lumio.watchlist.WatchedAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which coins Add to Watchlist offers, as which asset, and in which order. Only an asset Lumio can watch gets a row and a star. */
class WatchlistCandidatesTest {
    private val lumioEvm: (Long) -> Boolean = { it in setOf(1L, 56L, 999L, 8453L) }
    private fun evm(id: Long) = NetworkId(Networks.evm(id), testnet = false)
    private val sol = NetworkId(Networks.SOL_MAINNET, testnet = false)
    private val algo = NetworkId(Networks.ALGO_MAINNET, testnet = false)

    @Test
    fun aNetworksOwnCoinIsWatchedAsThatCoin() {
        assertEquals(WatchedAsset(NetworkId(Networks.BTC_MAINNET, testnet = false)), watchableAsset(DirectoryCoin("bitcoin", "Bitcoin", "btc"), lumioEvm))
        assertEquals(WatchedAsset(evm(1)), watchableAsset(DirectoryCoin("ethereum", "Ethereum", "eth"), lumioEvm))
        // BNB has an ERC-20 copy on Ethereum, but it is BNB Smart Chain's own coin.
        assertEquals(WatchedAsset(evm(56)), watchableAsset(DirectoryCoin("binancecoin", "BNB", "bnb", mapOf("eip155:1" to "0xb8c77482e45f1f44de1745f52c74426c631bdd52")), lumioEvm))
        assertEquals(WatchedAsset(evm(999)), watchableAsset(DirectoryCoin("hyperliquid", "Hyperliquid", "hype"), lumioEvm))
    }

    @Test
    fun aTokenIsWatchedOnEthereumFirstThenSolanaThenTheOtherNetworksThenAlgorand() {
        val contracts = mapOf(
            "algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k" to "31566704",
            "eip155:8453" to "0x833589FCD6EDB6E08F4C7C32D4F71B54BDA02913",
            "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp" to "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            "eip155:1" to "0xA0B86991C6218B36C1D19D4A2E9EB0CE3606EB48",
        )
        fun from(vararg keys: String) = watchableAsset(DirectoryCoin("usd-coin", "USDC", "usdc", contracts.filterKeys { it in keys }), lumioEvm)

        assertEquals(WatchedAsset(evm(1), "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"), from(*contracts.keys.toTypedArray()))
        assertEquals(WatchedAsset(sol, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"), from("eip155:8453", "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp", "algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k"))
        assertEquals(WatchedAsset(evm(8453), "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"), from("eip155:8453", "algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k"))
        assertEquals(WatchedAsset(algo, "31566704"), from("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k"))
    }

    @Test
    fun aCoinLumioCannotWatchHasNoAsset() {
        assertNull(watchableAsset(DirectoryCoin("ripple", "XRP", "xrp"), lumioEvm))
        assertNull(watchableAsset(DirectoryCoin("tether-on-polygon", "Tether", "usdt", mapOf("eip155:137" to "0xc2132d05d31c914a87c6611c10748aeb04b58e8f")), lumioEvm))
        assertNull(watchableAsset(DirectoryCoin("odd", "Odd", "odd", mapOf("eip155:1" to "not-a-contract")), lumioEvm))
    }

    @Test
    fun topByMarketCapKeepsTheSourcesOrderAndLeavesOutWhatLumioCannotWatch() {
        val top = MarketCoins(
            listOf(
                MarketCoin("bitcoin", "Bitcoin", "btc", 1, "https://img/btc"),
                MarketCoin("ripple", "XRP", "xrp", 6, "https://img/xrp"),
                MarketCoin("tether", "Tether", "usdt", 3, null),
            ),
            asOfMs = 0L,
        )
        val directory = CoinDirectory(listOf(DirectoryCoin("tether", "Tether", "usdt", mapOf("eip155:1" to "0xdac17f958d2ee523a2206206994597c13d831ec7"))), 0L)

        val rows = topCandidates(top, directory, lumioEvm)

        assertEquals(listOf("bitcoin", "tether"), rows.map { it.coinId })
        assertEquals(listOf(MarketRank.Place(1), MarketRank.Place(3)), rows.map { it.rank })
        assertEquals("https://img/btc", rows.first().image)
    }

    @Test
    fun aSearchFindsByNameOrTickerAndShowsOneRowPerNameRankedByMarketCap() {
        val directory = CoinDirectory(
            listOf(
                DirectoryCoin("backed", "Backed Bond", "bcsbgc3", mapOf("eip155:8453" to "0x" + "1".repeat(40))),
                DirectoryCoin("doge-2", "Government Efficiency", "doge", mapOf("eip155:1" to "0x" + "2".repeat(40))),
                DirectoryCoin("doge", "Government Efficiency", "doge", mapOf("eip155:1" to "0x" + "3".repeat(40))),
                DirectoryCoin("fund", "Government Fund", "ustb", mapOf("eip155:1" to "0x" + "4".repeat(40))),
                DirectoryCoin("xrp-gov", "Government XRP", "gxrp"),
                DirectoryCoin("other", "Other", "oth", mapOf("eip155:1" to "0x" + "5".repeat(40))),
            ),
            0L,
        )
        val matches = searchMatches("government", watchableEntries(directory, lumioEvm)) + searchMatches("bcsbgc3", watchableEntries(directory, lumioEvm))
        val markets = listOf(
            MarketCoin("doge", "Government Efficiency", "doge", 4004, null),
            MarketCoin("doge-2", "Government Efficiency", "doge", null, null),
            MarketCoin("fund", "Government Fund", "ustb", 85, "https://img/ustb"),
            MarketCoin("backed", "Backed Bond", "bcsbgc3", null, null),
        ).associateBy { it.id }

        val rows = searchCandidates(matches, markets)

        assertEquals(listOf("fund", "doge", "backed"), rows.map { it.coinId })
        assertEquals(listOf(MarketRank.Place(85), MarketRank.Place(4004), MarketRank.Unranked), rows.map { it.rank })
        assertEquals(listOf(0, 1, 0), rows.map { it.sameName })
    }

    @Test
    fun aResultWithNoMarketRowReadYetHasNoRankAndGoesLast() {
        val coin = DirectoryCoin("fund", "Government Fund", "ustb", mapOf("eip155:1" to "0x" + "4".repeat(40)))
        val other = DirectoryCoin("backed", "Backed Government Bond", "bcsbgc3", mapOf("eip155:1" to "0x" + "1".repeat(40)))
        val matches = searchMatches("government", watchableEntries(CoinDirectory(listOf(coin, other), 0L), lumioEvm))

        val rows = searchCandidates(matches, mapOf("backed" to MarketCoin("backed", "Backed Government Bond", "bcsbgc3", null, null)))

        assertEquals(listOf("backed", "fund"), rows.map { it.coinId })
        assertNull(rows.last().rank)
    }
}
