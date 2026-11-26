package app.lumio

import app.lensly.core.registry.TokenEntry
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.CoinDirectory
import app.lumio.data.provider.DirectoryCoin
import app.lumio.ui.search.directoryNames
import app.lumio.watchlist.WatchedAsset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Home, Add to Watchlist and the asset page name an asset the same way: the market source's name, with the kit's one override (USDC is "USD Coin"),
 * and the registry's only for an asset the source does not name.
 */
class CoinNamesTest {
    private val ethereum = NetworkId(Networks.evm(1), testnet = false)
    private val usdt = WatchedAsset(ethereum, "0xdac17f958d2ee523a2206206994597c13d831ec7")
    private val usdc = WatchedAsset(ethereum, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")
    private val link = WatchedAsset(ethereum, "0x514910771af9ca656af840dff83e8264ecf986ca")
    private val directory = CoinDirectory(
        listOf(
            DirectoryCoin("bitcoin", "Bitcoin", "btc"),
            DirectoryCoin("tether", "Tether", "usdt", mapOf("eip155:1" to "0xdAC17F958D2ee523a2206206994597C13D831ec7")),
            DirectoryCoin("usd-coin", "USDC", "usdc", mapOf("eip155:1" to "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48")),
            DirectoryCoin("chainlink", "Chainlink", "link", mapOf("eip155:1" to "0x514910771AF9Ca656af840dff83E8264EcF986CA")),
        ),
        0L,
    )

    private fun names(asset: WatchedAsset, listed: TokenEntry? = null, known: CoinDirectory? = directory) =
        namesOf(asset, listed, emptyList()) { known?.let { directoryNames(it, asset) } }

    private fun registry(symbol: String, name: String, contract: String) = TokenEntry(chainId = 1L, contract = contract, symbol = symbol, name = name, decimals = 6)

    @Test
    fun theMarketSourcesNameWinsOverTheRegistrys() {
        assertEquals("USDT" to "Tether", names(usdt, registry("USDT", "Tether USD", usdt.contract!!)))
        assertEquals("LINK" to "Chainlink", names(link, registry("LINK", "ChainLink Token", link.contract!!)))
    }

    @Test
    fun usdcIsTitledUsdCoinAsTheKitTitlesIt() {
        assertEquals("USDC" to "USD Coin", names(usdc))
    }

    @Test
    fun aNetworksOwnCoinTakesTheSourcesNameToo() {
        assertEquals("BTC" to "Bitcoin", names(WatchedAsset(NetworkId(Networks.BTC_MAINNET, testnet = false))))
    }

    @Test
    fun anAssetTheSourceDoesNotNameKeepsTheRegistrysName() {
        assertEquals("USDT" to "Tether USD", names(usdt, registry("USDT", "Tether USD", usdt.contract!!), known = null))
    }
}
