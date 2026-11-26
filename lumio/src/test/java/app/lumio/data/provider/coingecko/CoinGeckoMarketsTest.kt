package app.lumio.data.provider.coingecko

import app.lumio.data.config.ProviderConfig
import app.lumio.data.provider.CoinDirectoryRead
import app.lumio.data.provider.CoinMarkets
import app.lumio.data.provider.DirectoryCoin
import app.lumio.data.provider.Http
import app.lumio.data.provider.MarketCoin
import app.lumio.data.provider.Request
import app.lumio.data.provider.TopCoins
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The coins by market cap and the coin directory that Add to Watchlist reads, against a local MockWebServer with the shapes the source gave. */
class CoinGeckoMarketsTest {
    private val server = MockWebServer()
    private val charges = AtomicInteger()
    private val http = Http(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()) { charges.incrementAndGet() }

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun <T> call(req: Request<T>): T = runBlocking {
        CoinGeckoProvider().execute(req, ProviderConfig(baseUrl = server.url("/api/v3/").toString()), http)
    }

    private val markets = """[
        {"id":"bitcoin","symbol":"btc","name":"Bitcoin","image":"https://coin-images.coingecko.com/coins/images/1/large/bitcoin.png?1696501400","market_cap_rank":1},
        {"id":"backed","symbol":"bcsbgc3","name":"Backed Bond","image":"http://plain.example/large/x.png","market_cap_rank":null}
    ]"""

    @Test
    fun theTopCoinsAreTheFirstPageByMarketCapWithTheirRankAndSmallLogo() {
        server.enqueue(MockResponse.Builder().body(markets).build())

        val coins = call(TopCoins()).coins

        assertEquals(
            listOf(
                MarketCoin("bitcoin", "Bitcoin", "btc", 1, "https://coin-images.coingecko.com/coins/images/1/small/bitcoin.png?1696501400"),
                MarketCoin("backed", "Backed Bond", "bcsbgc3", null, null),
            ),
            coins,
        )
        val url = server.takeRequest().url
        assertEquals("/api/v3/coins/markets", url.encodedPath)
        assertEquals("usd", url.queryParameter("vs_currency"))
        assertEquals("market_cap_desc", url.queryParameter("order"))
        assertEquals("250", url.queryParameter("per_page"))
        assertEquals(null, url.queryParameter("ids"))
    }

    @Test
    fun aSearchReadsTheMarketRowsOfItsCoinsByTheirIds() {
        server.enqueue(MockResponse.Builder().body(markets).build())

        call(CoinMarkets(listOf("bitcoin", "backed")))

        assertEquals("bitcoin,backed", server.takeRequest().url.queryParameter("ids"))
    }

    @Test
    fun theDirectoryKeysEachContractByItsNetworkAndDropsNetworksWithNoChainId() {
        server.enqueue(
            MockResponse.Builder().body(
                """[{"id":"ethereum","chain_identifier":1},{"id":"solana","chain_identifier":null},{"id":"algorand","chain_identifier":null},
                    {"id":"tron","chain_identifier":null},{"id":"binance-smart-chain","chain_identifier":56}]""",
            ).build(),
        )
        server.enqueue(
            MockResponse.Builder().body(
                """[{"id":"bitcoin","symbol":"btc","name":"Bitcoin","platforms":{}},
                    {"id":"tether","symbol":"usdt","name":"Tether","platforms":{"ethereum":"0xdAC17F958D2ee523a2206206994597C13D831ec7",
                      "solana":"Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB","tron":"TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t","binance-smart-chain":""}},
                    {"id":"usd-coin","symbol":"usdc","name":"USDC","platforms":{"algorand":"31566704","":""}}]""",
            ).build(),
        )

        val coins = call(CoinDirectoryRead()).coins

        assertEquals(
            listOf(
                DirectoryCoin("bitcoin", "Bitcoin", "btc"),
                DirectoryCoin(
                    "tether",
                    "Tether",
                    "usdt",
                    mapOf(
                        "eip155:1" to "0xdAC17F958D2ee523a2206206994597C13D831ec7",
                        "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp" to "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
                    ),
                ),
                DirectoryCoin("usd-coin", "USDC", "usdc", mapOf("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k" to "31566704")),
            ),
            coins,
        )
        assertEquals("/api/v3/asset_platforms", server.takeRequest().url.encodedPath)
        val list = server.takeRequest().url
        assertEquals("/api/v3/coins/list", list.encodedPath)
        assertEquals("true", list.queryParameter("include_platform"))
        assertEquals(1, charges.get())
    }
}
