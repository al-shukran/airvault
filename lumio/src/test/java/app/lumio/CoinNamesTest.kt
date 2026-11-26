package app.lumio

import app.lensly.core.registry.TokenEntry
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.CoinDirectory
import app.lumio.data.provider.DirectoryCoin
import app.lumio.data.provider.MarketCoin
import app.lumio.data.provider.MarketCoins
import app.lumio.ui.search.topCandidates
import app.lumio.watchlist.CoinIndex
import app.lumio.watchlist.WalletWatchlist
import app.lumio.watchlist.WatchedAsset
import app.lumio.watchlist.WatchedLabel
import app.lumio.watchlist.toggled
import app.lumio.watchlist.topList
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Home, Add to Watchlist and the asset page name an asset the same way. A coin starred from Add to Watchlist keeps the market source's name with it (USDC
 * is "USD Coin", as the kit titles it), so Home names it with no read of the source and no index; the registry's name is for an asset with none kept.
 */
class CoinNamesTest {
    private val ethereum = NetworkId(Networks.evm(1), testnet = false)
    private val usdt = WatchedAsset(ethereum, "0xdac17f958d2ee523a2206206994597c13d831ec7")
    private val registryUsdt = TokenEntry(chainId = 1L, contract = usdt.contract!!, symbol = "USDT", name = "Tether USD", decimals = 6)

    /** The label Add to Watchlist keeps when the coin [id] of the market list is starred. */
    private fun starred(id: String, name: String, symbol: String, contract: String): WatchedLabel {
        val index = CoinIndex.of(CoinDirectory(listOf(DirectoryCoin(id, name, symbol, mapOf("eip155:1" to contract))), 0L)) { it == 1L }
        val row = topCandidates(topList(MarketCoins(listOf(MarketCoin(id, name, symbol, 3, "https://img/$id")), 0L), index) { it == 1L }).single()
        return WalletWatchlist().toggled(row.asset, row.label).labels.getValue(row.asset.key)
    }

    @Test
    fun aCoinStarredFromAddToWatchlistKeepsTheSourcesNameOverTheRegistrys() {
        val label = starred("tether", "Tether", "usdt", usdt.contract!!)

        assertEquals(WatchedLabel("USDT", "Tether", "https://img/tether"), label)
        assertEquals("USDT" to "Tether", namesOf(usdt, registryUsdt, emptyList(), label))
    }

    @Test
    fun usdcIsTitledUsdCoinAsTheKitTitlesIt() {
        val label = starred("usd-coin", "USDC", "usdc", "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")

        assertEquals("USDC" to "USD Coin", label.symbol to label.name)
    }

    @Test
    fun anAssetWithNoKeptNamesTakesTheRegistrysAndANetworksCoinItsOwn() {
        assertEquals("USDT" to "Tether USD", namesOf(usdt, registryUsdt, emptyList()))
        assertEquals("BTC" to "Bitcoin", namesOf(WatchedAsset(NetworkId(Networks.BTC_MAINNET, testnet = false)), null, emptyList()))
    }
}
