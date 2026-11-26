package app.lumio.ui.search

import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.CoinDirectory
import app.lumio.data.provider.DirectoryCoin
import app.lumio.data.provider.MarketCoin
import app.lumio.data.provider.MarketCoins
import app.lumio.watchlist.CoinIndex
import app.lumio.watchlist.WatchedAsset
import app.lumio.watchlist.topList
import app.lumio.watchlist.watchableAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which coins Add to Watchlist offers, as which asset, under which name, and in which order. Only an asset Lumio can watch gets a row and a star. */
class WatchlistCandidatesTest {
    private val lumioEvm: (Long) -> Boolean = { it in setOf(1L, 56L, 999L, 8453L) }
    private fun evm(id: Long) = NetworkId(Networks.evm(id), testnet = false)
    private val sol = NetworkId(Networks.SOL_MAINNET, testnet = false)
    private val algo = NetworkId(Networks.ALGO_MAINNET, testnet = false)

    private fun token(id: String, name: String, symbol: String, digit: Char) = DirectoryCoin(id, name, symbol, mapOf("eip155:1" to "0x" + digit.toString().repeat(40)))

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
        // An Algorand asset ID is a 64-bit number; a longer one names no asset.
        assertNull(watchableAsset(DirectoryCoin("big", "Big", "big", mapOf("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k" to "99999999999999999999")), lumioEvm))
        assertEquals(WatchedAsset(algo, "18446744073709551615"), watchableAsset(DirectoryCoin("max", "Max", "max", mapOf("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k" to "18446744073709551615")), lumioEvm))
    }

    @Test
    fun topByMarketCapKeepsTheSourcesOrderAndLeavesOutWhatLumioCannotWatch() {
        val markets = MarketCoins(
            listOf(
                MarketCoin("bitcoin", "Bitcoin", "btc", 1, "https://img/btc"),
                MarketCoin("ripple", "XRP", "xrp", 6, "https://img/xrp"),
                MarketCoin("tether", "Tether", "usdt", 3, null),
                MarketCoin("usd-coin", "USDC", "usdc", 5, null),
            ),
            asOfMs = 0L,
        )
        val index = CoinIndex.of(CoinDirectory(listOf(token("tether", "Tether", "usdt", 'd'), token("usd-coin", "USDC", "usdc", 'a')), 0L), lumioEvm)

        val rows = topCandidates(topList(markets, index, lumioEvm))

        assertEquals(listOf("bitcoin", "tether", "usd-coin"), rows.map { it.coinId })
        assertEquals(listOf(MarketRank.Place(1), MarketRank.Place(3), MarketRank.Place(5)), rows.map { it.rank })
        // The market source's names, with the kit's one override.
        assertEquals(listOf("Bitcoin", "Tether", "USD Coin"), rows.map { it.name })
        assertEquals(listOf("BTC", "USDT", "USDC"), rows.map { it.symbol })
        assertEquals("https://img/btc", rows.first().image)
    }

    @Test
    fun withNoIndexTheTopListHoldsTheNetworksOwnCoinsAlone() {
        val markets = MarketCoins(listOf(MarketCoin("bitcoin", "Bitcoin", "btc", 1, null), MarketCoin("tether", "Tether", "usdt", 3, null)), 0L)

        assertEquals(listOf("bitcoin"), topList(markets, null, lumioEvm).coins.map { it.coin.id })
    }

    @Test
    fun aSearchShowsOneRowPerNameRankedByMarketCapWithTheTopListsRanksFirst() {
        val index = CoinIndex.of(
            CoinDirectory(
                listOf(
                    token("backed", "Backed Government Bond", "bcsbgc3", '1'),
                    token("doge-2", "Government Efficiency", "doge", '2'),
                    token("doge", "Government Efficiency", "doge", '3'),
                    token("fund", "Government Fund", "ustb", '4'),
                    token("other", "Other", "oth", '5'),
                ),
                0L,
            ),
            lumioEvm,
        )
        val top = topList(MarketCoins(listOf(MarketCoin("fund", "Government Fund", "ustb", 85, "https://img/ustb")), 0L), index, lumioEvm)

        val first = searchCandidates("government", index, top, emptyMap())
        // The top list ranks the fund at once; the others wait for their market rows, which are read for them alone.
        assertEquals(listOf("fund", "doge-2", "backed"), first.map { it.coinId })
        assertEquals(listOf("doge-2", "backed"), unrankedIds(first))

        val markets = listOf(
            MarketCoin("doge", "Government Efficiency", "doge", 4004, null),
            MarketCoin("doge-2", "Government Efficiency", "doge", null, null),
            MarketCoin("backed", "Backed Government Bond", "bcsbgc3", null, null),
        ).associateBy { it.id }
        val rows = searchCandidates("government", index, top, markets)

        assertEquals(listOf("fund", "doge", "backed"), rows.map { it.coinId })
        assertEquals(listOf(MarketRank.Place(85), MarketRank.Place(4004), MarketRank.Unranked), rows.map { it.rank })
        assertEquals(listOf(0, 1, 0), rows.map { it.sameName })
        assertEquals("https://img/ustb", rows.first().image)
    }

    @Test
    fun aSearchKeepsTheFiftyBestMatchesExactAndPrefixMatchesFirst() {
        val many = (1..80).map { DirectoryCoin("coin-$it", "Wrapped Gold $it", "wg$it", mapOf("eip155:1" to "0x" + it.toString().padStart(40, '0'))) }
        val exact = DirectoryCoin("gold", "Gold", "gold", mapOf("eip155:1" to "0x" + "f".repeat(40)))
        val prefix = DirectoryCoin("goldfinch", "Goldfinch", "gfi", mapOf("eip155:1" to "0x" + "e".repeat(40)))
        val index = CoinIndex.of(CoinDirectory(many + prefix + exact, 0L), lumioEvm)

        val rows = searchCandidates("gold", index, null, emptyMap())

        assertEquals(MAX_RESULTS, rows.size)
        assertEquals(listOf("gold", "goldfinch"), rows.take(2).map { it.coinId })
        assertEquals(MAX_RESULTS, unrankedIds(rows).size)
    }
}
