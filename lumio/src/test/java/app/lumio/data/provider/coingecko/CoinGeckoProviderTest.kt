package app.lumio.data.provider.coingecko

import app.lumio.data.BTC
import app.lumio.data.ETH
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.FiatRates
import app.lumio.data.provider.BitcoinMarket
import app.lumio.data.provider.Http
import app.lumio.data.provider.MarketQuote
import app.lumio.data.provider.NativePrice
import app.lumio.data.provider.OpKey
import app.lumio.data.provider.Ops
import app.lumio.data.provider.PriceQuote
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.LogoImage
import app.lumio.data.provider.Request
import app.lumio.data.provider.Trait
import app.lumio.data.provider.TrendingCoin
import app.lumio.data.provider.TrendingCoins
import app.lumio.data.provider.TokenLogo
import app.lumio.data.provider.TrendingList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The CoinGecko plugin against a local MockWebServer. The answers are the shapes the endpoints gave on the days their sources were read (see
 * [CoinGeckoProvider]). No test reaches a network, and a key is sent only in the header.
 */
class CoinGeckoProviderTest {
    private val server = MockWebServer()
    private val http = Http(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build())

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun cfg(key: String? = null) = ProviderConfig(baseUrl = server.url("/api/v3/").toString(), apiKey = key)

    private fun <T> call(req: Request<T>, key: String? = null, client: Http = http): T = runBlocking { CoinGeckoProvider().execute(req, cfg(key), client) }

    /** A price is kept for a keyed CoinGecko, so the price tests send the demo key. */
    private fun price(req: NativePrice, key: String? = DEMO_KEY): PriceQuote = call(req, key)

    private fun ethereumAnswer(change: String = "0.4118") = MockResponse.Builder().body(
        """{"ethereum":{"usd":1827.5,"usd_24h_change":$change,"last_updated_at":1794774853}}""",
    ).build()

    private fun exchangeRatesAnswer(eur: String = "50000") = MockResponse.Builder().body(
        """{"rates":{"btc":{"name":"Bitcoin","unit":"BTC","value":1.0,"type":"crypto"},""" +
            """"usd":{"name":"US Dollar","unit":"USD","value":100000.0,"type":"fiat"},""" +
            """"eur":{"name":"Euro","unit":"€","value":$eur,"type":"fiat"},""" +
            """"chf":{"name":"Swiss Franc","unit":"CHF","value":25000.0,"type":"fiat"}}}""",
    ).build()

    private fun bitcoinAnswer(cap: String = "1175864307874.9705", change: String = "-1.6095249174253978") = MockResponse.Builder().body(
        """{"bitcoin":{"usd":58451,"usd_market_cap":$cap,"usd_24h_change":$change,"last_updated_at":1795625974}}""",
    ).build()

    @Test
    fun thePriceChangeAndTimeComeFromOneSimplePriceRequest() {
        server.enqueue(ethereumAnswer())

        assertEquals(PriceQuote(usd = 1827.5, change24hPct = 0.4118, asOfMs = 1_794_774_853_000L), price(NativePrice(ETH)))
        val url = server.takeRequest().url
        assertEquals("/api/v3/simple/price", url.encodedPath)
        assertEquals("ethereum", url.queryParameter("ids"))
        assertEquals("usd", url.queryParameter("vs_currencies"))
        assertEquals("true", url.queryParameter("include_24hr_change"))
        assertEquals("true", url.queryParameter("include_last_updated_at"))
    }

    @Test
    fun aPriceIsNeverAskedWithoutAKey() {
        assertFailsWith<ProviderError.Unsupported> { price(NativePrice(ETH), key = null) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aDemoKeyGoesInTheHeaderAndNeverInTheUrl() {
        server.enqueue(ethereumAnswer())

        price(NativePrice(ETH), key = "DEMO-KEY-123")
        val request = server.takeRequest()
        assertEquals("DEMO-KEY-123", request.headers["x-cg-demo-api-key"])
        assertFalse(request.url.toString().contains("DEMO-KEY-123"))
    }

    @Test
    fun aNullChangeIsNoChange() {
        server.enqueue(ethereumAnswer(change = "null"))

        assertNull(price(NativePrice(ETH)).change24hPct)
    }

    @Test
    fun aCoinTheSourceDoesNotKnowIsUnsupported() {
        server.enqueue(MockResponse.Builder().body("{}").build())

        assertFailsWith<ProviderError.Unsupported> { price(NativePrice(ETH)) }
    }

    @Test
    fun aRateLimitWithoutRetryAfterLeavesTheCooldownToTheScheduler() {
        server.enqueue(MockResponse.Builder().code(429).build())

        assertNull(assertFailsWith<ProviderError.RateLimited> { price(NativePrice(BTC)) }.retryAfterMs)
    }

    @Test
    fun aTestnetIsUnsupportedAndNeverCalled() {
        assertFailsWith<ProviderError.Unsupported> { price(NativePrice(NetworkId(Networks.evm(1), testnet = true))) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theMarketAnswersWithoutAKeyAndTheKeyIsSentWhenSet() {
        server.enqueue(bitcoinAnswer())
        server.enqueue(bitcoinAnswer())

        call(BitcoinMarket())
        assertNull(server.takeRequest().headers["x-cg-demo-api-key"])
        call(BitcoinMarket(), key = DEMO_KEY)
        assertEquals(DEMO_KEY, server.takeRequest().headers["x-cg-demo-api-key"])
    }

    @Test
    fun theMarketIsBitcoinsCapAndItsChangeAndTimeFromTheSimplePriceEndpoint() {
        server.enqueue(bitcoinAnswer())

        assertEquals(MarketQuote(capUsd = 1175864307874.9705, change24hPct = -1.6095249174253978, asOfMs = 1_795_625_974_000L), call(BitcoinMarket()))
        val url = server.takeRequest().url
        assertEquals("/api/v3/simple/price", url.encodedPath)
        assertEquals("bitcoin", url.queryParameter("ids"))
        assertEquals("true", url.queryParameter("include_market_cap"))
    }

    @Test
    fun aMarketWithNoChangeHasNoChange() {
        server.enqueue(bitcoinAnswer(change = "null"))

        assertNull(call(BitcoinMarket()).change24hPct)
    }

    @Test
    fun aMarketCapThatIsNotPositiveIsAFatalAnswer() {
        server.enqueue(bitcoinAnswer(cap = "0"))

        assertFailsWith<ProviderError.Fatal> { call(BitcoinMarket()) }
    }

    @Test
    fun theTrendingCoinsAreListedInTheSourcesOrderWithTheirPriceAndChange() {
        server.enqueue(
            MockResponse.Builder().body(
                """{"coins":[
                    {"item":{"id":"bonk","name":"Bonk","symbol":"BONK","market_cap_rank":103,
                      "data":{"price":0.000005938674704566297,"price_change_percentage_24h":{"usd":-3.2712597351582655},"market_cap":"${'$'}521,967,819"}}},
                    {"item":{"id":"new","name":"New Coin","symbol":"NEW","market_cap_rank":null,
                      "data":{"price":null,"price_change_percentage_24h":null}}}
                ],"nfts":[],"categories":[]}""",
            ).build(),
        )

        val list = call(TrendingCoins())

        assertEquals(
            listOf(
                TrendingCoin(name = "Bonk", symbol = "BONK", rank = 103, priceUsd = 0.000005938674704566297, change24hPct = -3.2712597351582655),
                TrendingCoin(name = "New Coin", symbol = "NEW", rank = null, priceUsd = null, change24hPct = null),
            ),
            list.coins,
        )
        assertTrue(list.asOfMs > 0L)
        assertEquals("/api/v3/search/trending", server.takeRequest().url.encodedPath)
    }

    @Test
    fun aTrendingListWithoutCoinsIsAnEmptyAnswer() {
        server.enqueue(MockResponse.Builder().body("""{"coins":[]}""").build())

        assertEquals(emptyList<TrendingCoin>(), call(TrendingCoins()).coins)
    }

    @Test
    fun theProviderIsKeyOptionalAndOnlyItsPriceNeedsAKey() {
        val provider = CoinGeckoProvider()
        assertEquals(setOf(Trait.KEY_OPTIONAL), provider.traits)
        assertFalse(provider.supports(BTC, Ops.PRICE_NATIVE, ProviderConfig()))
        assertTrue(provider.supports(BTC, Ops.PRICE_NATIVE, ProviderConfig(apiKey = DEMO_KEY)))
        assertTrue(provider.supports(BTC, Ops.MARKET_BITCOIN, ProviderConfig()))
        assertTrue(provider.supports(BTC, Ops.MARKET_TRENDING, ProviderConfig()))
        assertFalse(provider.supports(BTC, OpKey.on(BTC, "nativeBalance"), ProviderConfig()))
        assertFalse(provider.defaultLimits(ProviderConfig()) == provider.defaultLimits(ProviderConfig(apiKey = DEMO_KEY)))
    }

    @Test
    fun aTokenLogoIsTheImageItsRecordNamesAndNeedsNoKey() {
        val image = "logo-bytes".encodeToByteArray()
        server.enqueue(MockResponse.Builder().body("""{"image":{"small":"https://$IMAGE_HOST/img/small.png"}}""").build())
        val served = ArrayList<HttpUrl>()
        val charges = AtomicInteger()

        val logo: LogoImage = call(TokenLogo(ETH, USDC), client = logoHttp(image, served, charges))

        assertContentEquals(image, logo.bytes)
        val record = server.takeRequest()
        assertEquals("/api/v3/coins/ethereum/contract/$USDC", record.target)
        assertNull(record.headers["x-cg-demo-api-key"])
        assertEquals("/img/small.png", served.single().encodedPath)
        assertEquals(1, charges.get())
    }

    @Test
    fun aNullSmallLogoFallsBackToThumb() {
        server.enqueue(MockResponse.Builder().body("""{"image":{"small":null,"thumb":"https://$IMAGE_HOST/img/thumb.png"}}""").build())
        val served = ArrayList<HttpUrl>()

        call(TokenLogo(ETH, USDC), client = logoHttp("logo-bytes".encodeToByteArray(), served))

        assertEquals("/img/thumb.png", served.single().encodedPath)
    }

    @Test
    fun aTokenTheSourceHasNoRecordForIsNotFoundAndNotAFatalAnswer() {
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":"Coin not found"}""").build())

        assertFailsWith<ProviderError.NotFound> { call(TokenLogo(ETH, USDC)) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aLogoOverPlainHttpIsRefusedBeforeItIsFetched() {
        server.enqueue(MockResponse.Builder().body("""{"image":{"small":"http://$IMAGE_HOST/img/small.png"}}""").build())
        val served = ArrayList<HttpUrl>()

        assertFailsWith<ProviderError.Fatal> { call(TokenLogo(ETH, USDC), client = logoHttp("logo-bytes".encodeToByteArray(), served)) }
        assertTrue(served.isEmpty())
    }

    /** A client whose image host answers [image] with no network, so a logo URL can be HTTPS. Its requests are listed in [served]. */
    private fun logoHttp(image: ByteArray, served: MutableList<HttpUrl>, charges: AtomicInteger = AtomicInteger()): Http = Http(
        OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).addInterceptor { chain ->
            if (chain.request().url.host != IMAGE_HOST) return@addInterceptor chain.proceed(chain.request())
            served.add(chain.request().url)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(image.toResponseBody()).build()
        }.build(),
    ) { charges.incrementAndGet() }

    @Test
    fun aTokenOffTheEvmMainnetsHasNoLogoAndIsNeverAsked() {
        assertFalse(CoinGeckoProvider().supports(BTC, Ops.LOGO_TOKEN, ProviderConfig()))
        assertFailsWith<ProviderError.Unsupported> { call(TokenLogo(NetworkId(Networks.evm(1), testnet = true), USDC)) }
        assertEquals(0, server.requestCount)
    }

    private companion object {
        const val DEMO_KEY = "demo-key"
        const val USDC = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
        const val IMAGE_HOST = "img.example"
    }

    @Test
    fun theFiatRatesAreEachCurrencyPerUsDollarFromTheBitcoinTable() {
        server.enqueue(exchangeRatesAnswer())

        assertEquals(mapOf("USD" to 1.0, "EUR" to 0.5, "CHF" to 0.25), call(FiatRates()).perUsd)
        assertEquals("/api/v3/exchange_rates", server.takeRequest().url.encodedPath)
    }

    @Test
    fun aFiatRateThatIsNotPositiveIsAFatalAnswer() {
        server.enqueue(exchangeRatesAnswer(eur = "0"))

        assertFailsWith<ProviderError.Fatal> { call(FiatRates()) }
    }
}
