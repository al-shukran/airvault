package app.lumio.data.price

import app.lumio.data.ETH
import app.lumio.data.FakeClock
import app.lumio.data.FakeKvDao
import app.lumio.data.FakeProvider
import app.lumio.data.asset.AssetId
import app.lumio.data.cache.Cache
import app.lumio.data.cache.CacheKeys
import app.lumio.data.cache.CachePolicy
import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.FiatQuote
import app.lumio.data.provider.Http
import app.lumio.data.provider.MARKET_NET
import app.lumio.data.provider.Ops
import app.lumio.data.provider.PriceQuote
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderCatalog
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.TrendingCoin
import app.lumio.data.provider.TrendingList
import app.lumio.data.sched.Scheduler
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The price repository over the real scheduler, with DefiLlama and CoinGecko on one local MockWebServer and a clock the test moves by hand. A stale
 * price is checked with a scripted provider, so no test waits out a real retry backoff.
 */
class PriceRepositoryTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = Http(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build())
    private val clock = FakeClock(1_000_000L)
    private val kv = FakeKvDao()
    private val store = Cache(kv, CacheKeys(ByteArray(32) { 3 }, ByteArray(32) { 5 }), clock)
    private val fast = Limits(rps = 1_000.0, burst = 1_000, maxConcurrent = 10)
    private val routes = Routes()

    @Before
    fun start() {
        server.start()
        server.dispatcher = routes
    }

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    /**
     * The repository over [providers], with the local server as each provider's base, [saved] as the owner's settings (with the price defaults of
     * [ProviderCatalog.withDefaults]), and [kept] as the open vault's cache.
     */
    private fun repository(providers: List<Provider>, saved: Map<String, ProviderConfig> = emptyMap(), kept: Cache? = store): PriceRepository {
        val urls = mapOf("defillama" to server.url("/").toString(), "coingecko" to server.url("/cg/api/v3/").toString())
        val configs = ProviderCatalog.withDefaults(saved).mapValues { (id, cfg) -> cfg.copy(baseUrl = urls[id], limits = fast) }
        val scheduler = Scheduler(providers, { configs }, http, clock, Random(7), scope) { kept }
        return PriceRepository(scheduler) { kept }
    }

    private fun live(): List<Provider> = ProviderCatalog.priceProviders()

    /** A CoinGecko key that is set, and enabled unless [enabled] is false. */
    private fun keyed(enabled: Boolean = true): Map<String, ProviderConfig> =
        mapOf("coingecko" to ProviderConfig(enabled = enabled, apiKey = "demo-key"))

    private fun priceOf(price: String, timestamp: Long): MockResponse = MockResponse.Builder().body(
        """{"coins":{"coingecko:ethereum":{"price":$price,"symbol":"ETH","timestamp":$timestamp,"confidence":0.999}}}""",
    ).build()

    private fun changeOf(value: String): MockResponse = MockResponse.Builder().body("""{"coins":{"coingecko:ethereum":$value}}""").build()

    @Test
    fun withNoKeyOnlyDefiLlamaIsAskedAndCoinGeckoIsNever() = runBlocking {
        routes.on("prices", priceOf("1831.25", 1_794_774_850L))
        routes.on("percentage", changeOf("0.5"))
        val repository = repository(live())

        assertEquals(1_831.25, repository.prices(listOf(ETH))[ETH]!!.usd, 0.0)
        assertEquals(1, routes.calls("prices"))
        assertEquals(0, routes.calls("cg"))
    }

    @Test
    fun withAKeyCoinGeckoIsAskedFirstAndNoDefiLlamaCallIsMade() = runBlocking {
        routes.on("cg", MockResponse.Builder().body("""{"ethereum":{"usd":1827.5,"usd_24h_change":0.41,"last_updated_at":1794774853}}""").build())
        val repository = repository(live(), saved = keyed())

        assertEquals(Price(usd = 1827.5, change24hPct = 0.41, asOfMs = 1_794_774_853_000L, stale = false), repository.prices(listOf(ETH))[ETH])
        assertEquals(1, routes.calls("cg"))
        assertEquals(0, routes.calls("prices"))
        assertEquals(listOf("demo-key"), routes.keysSent())
    }

    @Test
    fun withAKeyARateLimitedCoinGeckoFallsBackToDefiLlamaAndStaysCooled() = runBlocking {
        routes.on("cg", MockResponse.Builder().code(429).setHeader("Retry-After", "600").build())
        routes.on("prices", priceOf("1827.5", 1_794_774_850L), priceOf("1828.0", 1_794_774_900L))
        routes.on("percentage", changeOf("0.41"), changeOf("0.42"))
        val repository = repository(live(), saved = keyed())

        assertEquals(1_827.5, repository.prices(listOf(ETH))[ETH]!!.usd, 0.0)
        clock.now += 61_000L
        assertEquals(1_828.0, repository.prices(listOf(ETH))[ETH]!!.usd, 0.0)
        assertEquals(1, routes.calls("cg"))
        assertEquals(2, routes.calls("prices"))
    }

    @Test
    fun withAKeyAFailedCoinGeckoAnswerFallsBackToDefiLlama() = runBlocking {
        routes.on("cg", MockResponse.Builder().body("""{"ethereum":{"usd":0,"usd_24h_change":0.41,"last_updated_at":1794774853}}""").build())
        routes.on("prices", priceOf("1831.25", 1_794_774_850L))
        routes.on("percentage", changeOf("0.5"))
        val repository = repository(live(), saved = keyed())

        assertEquals(1_831.25, repository.prices(listOf(ETH))[ETH]!!.usd, 0.0)
        assertEquals(1, routes.calls("cg"))
        assertEquals(1, routes.calls("prices"))
    }

    @Test
    fun withAKeyButCoinGeckoSwitchedOffDefiLlamaAloneAnswers() = runBlocking {
        routes.on("prices", priceOf("1831.25", 1_794_774_850L))
        routes.on("percentage", changeOf("0.5"))
        val repository = repository(live(), saved = keyed(enabled = false))

        assertEquals(1_831.25, repository.prices(listOf(ETH))[ETH]!!.usd, 0.0)
        assertEquals(0, routes.calls("cg"))
    }

    @Test
    fun aPriceIsReusedFromMemoryWithinTheTtlAndFetchedAgainAfterIt() = runBlocking {
        routes.on("prices", priceOf("1831.25", 1_794_774_850L), priceOf("1832.5", 1_794_774_900L))
        routes.on("percentage", changeOf("0.5"), changeOf("0.6"))
        val repository = repository(live())

        assertEquals(1_831.25, repository.prices(listOf(ETH))[ETH]!!.usd, 0.0)
        clock.now += 30_000L
        assertEquals(1_831.25, repository.prices(listOf(ETH))[ETH]!!.usd, 0.0)
        assertEquals(1, routes.calls("prices"))

        clock.now += 31_000L
        assertEquals(1_832.5, repository.prices(listOf(ETH))[ETH]!!.usd, 0.0)
        assertEquals(2, routes.calls("prices"))
    }

    @Test
    fun whenNoSourceAnswersTheLastPriceKeptIsReturnedAsStale() = runBlocking {
        val source = FakeProvider(id = "defillama", networks = setOf(ETH), ops = setOf(Ops.PRICE_NATIVE))
        source.outcomes.add(PriceQuote(usd = 1831.25, change24hPct = 0.5, asOfMs = 1_000L))
        source.outcomes.add(ProviderError.Fatal("The provider is down."))
        val repository = repository(listOf(source))

        assertEquals(Price(usd = 1831.25, change24hPct = 0.5, asOfMs = 1_000L, stale = false), repository.prices(listOf(ETH))[ETH])
        clock.now += 10 * 60_000L

        assertEquals(Price(usd = 1831.25, change24hPct = 0.5, asOfMs = 1_000L, stale = true), repository.prices(listOf(ETH))[ETH])
    }

    @Test
    fun withNoKeptPriceAndNoAnswerTheNetworkHasNoPrice() = runBlocking {
        val source = FakeProvider(id = "defillama", networks = setOf(ETH), ops = setOf(Ops.PRICE_NATIVE))
        source.outcomes.add(ProviderError.Fatal("The provider is down."))

        assertNull(repository(listOf(source)).prices(listOf(ETH))[ETH])
    }

    @Test
    fun aTestnetHasNoPriceAndNoSourceIsAsked() = runBlocking {
        val testnet = NetworkId(Networks.evm(1), testnet = true)
        val monadTestnet = NetworkId(Networks.evm(10143), testnet = true)

        assertEquals(emptyMap<NetworkId, Price>(), repository(live()).prices(listOf(testnet, monadTestnet)))
        assertEquals(0, routes.total())
    }

    @Test
    fun aMixedRequestPricesEachNetworkThatHasAPriceAndOmitsTheRest() = runBlocking {
        routes.on("prices", priceOf("1831.25", 1_794_774_850L))
        routes.on("percentage", changeOf("0.5"))
        val unpriced = NetworkId(Networks.evm(250), testnet = false)
        val testnet = NetworkId(Networks.evm(1), testnet = true)

        val prices = repository(live()).prices(listOf(ETH, unpriced, testnet))

        assertEquals(setOf(ETH), prices.keys)
        assertEquals(1, routes.calls("prices"))
    }

    @Test
    fun withTheVaultLockedAFailedSourceGivesNoStalePrice() = runBlocking {
        val source = FakeProvider(id = "defillama", networks = setOf(ETH), ops = setOf(Ops.PRICE_NATIVE))
        source.outcomes.add(PriceQuote(usd = 1831.25, change24hPct = 0.5, asOfMs = 1_000L))
        source.outcomes.add(ProviderError.Fatal("The provider is down."))
        val repository = repository(listOf(source), kept = null)

        assertEquals(1_831.25, repository.prices(listOf(ETH))[ETH]!!.usd, 0.0)
        clock.now += 10 * 60_000L
        assertNull(repository.prices(listOf(ETH))[ETH])
    }

    @Test
    fun theTrendingListIsKeptAndReadBackFromTheVaultWhenNoSourceAnswers() = runBlocking {
        val coin = TrendingCoin(name = "Bonk", symbol = "BONK", rank = 103, priceUsd = 0.00001, change24hPct = -3.2)
        val source = FakeProvider(id = "coingecko", networks = setOf(MARKET_NET), ops = setOf(Ops.MARKET_TRENDING))
        source.outcomes.add(TrendingList(coins = listOf(coin), asOfMs = 1_000L))
        source.outcomes.add(ProviderError.Fatal("The provider is down."))
        val repository = repository(listOf(source))

        assertEquals(Kept(TrendingList(listOf(coin), asOfMs = 1_000L), stale = false), repository.trending())
        clock.now += 10 * 60_000L
        assertEquals(Kept(TrendingList(listOf(coin), asOfMs = 1_000L), stale = true), repository.trending())
        assertEquals(Kept(TrendingList(listOf(coin), asOfMs = 1_000L), stale = true), repository.lastTrending())
    }

    @Test
    fun theLastPricesAreReadFromTheVaultWithNoSourceAsked() = runBlocking {
        val source = FakeProvider(id = "defillama", networks = setOf(ETH), ops = setOf(Ops.PRICE_NATIVE))
        source.outcomes.add(PriceQuote(usd = 1831.25, change24hPct = 0.5, asOfMs = 1_000L))
        val repository = repository(listOf(source))
        repository.prices(listOf(ETH))

        val unpriced = AssetId.Native(NetworkId(Networks.evm(250), testnet = false))
        val expected: Map<AssetId, Price> = mapOf(AssetId.Native(ETH) to Price(usd = 1831.25, change24hPct = 0.5, asOfMs = 1_000L, stale = true))
        assertEquals(expected, repository.lastPrices(listOf(AssetId.Native(ETH), unpriced)))
        assertEquals(1, source.calls)
    }

    private val platforms = """[{"id":"ethereum","chain_identifier":1}]"""
    private val directory = """[{"id":"bitcoin","symbol":"btc","name":"Bitcoin","platforms":{}},""" +
        """{"id":"ousg","symbol":"ousg","name":"Ondo Fund","platforms":{"ethereum":"0x1b19c19393e2d034d8ff31ff34c81252fcbbee92"}}]"""
    private val markets = """[{"id":"bitcoin","symbol":"btc","name":"Bitcoin","image":null,"market_cap_rank":1},""" +
        """{"id":"ousg","symbol":"ousg","name":"Ondo Fund","image":null,"market_cap_rank":120}]"""
    private val lumioEvm: (Long) -> Boolean = { it == 1L }

    @Test
    fun theCoinIndexIsReadOnceForCallersAtTheSameTimeAndNeverKeptInTheVault() = runBlocking {
        routes.on("other", MockResponse.Builder().body(platforms).build(), MockResponse.Builder().body(directory).build())
        val repository = repository(live())

        val indexes = (1..3).map { scope.async { repository.coinIndex(lumioEvm) } }.awaitAll()

        assertEquals(listOf(listOf("bitcoin", "ousg")), indexes.map { index -> index!!.coins.map { it.id } }.distinct())
        assertEquals(2, routes.total())
        assertEquals(0, kv.all().size)
        assertEquals(null, repository(live()).knownCoinIndex())
    }

    @Test
    fun aFailedIndexReadIsTriedAgainByTheNextCall() = runBlocking {
        routes.on("other", MockResponse.Builder().code(404).build())
        val repository = repository(live())
        assertEquals(null, repository.coinIndex(lumioEvm))

        routes.on("other", MockResponse.Builder().body(platforms).build(), MockResponse.Builder().body(directory).build())
        assertEquals(listOf("bitcoin", "ousg"), repository.coinIndex(lumioEvm)?.coins?.map { it.id })
    }

    @Test
    fun topByMarketCapIsKeptSmallAndAKeptListThatDoesNotReadIsDroppedAndReadAgain() = runBlocking {
        routes.on("other", MockResponse.Builder().body(markets).build(), MockResponse.Builder().body(platforms).build(), MockResponse.Builder().body(directory).build())
        assertEquals(listOf("bitcoin", "ousg"), repository(live()).topCoins(lumioEvm)?.value?.coins?.map { it.coin.id })
        assertEquals(listOf("bitcoin", "ousg"), repository(live()).lastTopCoins()?.value?.coins?.map { it.coin.id })

        store.put(CachePolicy.LastKnown, TOP_COINS_KEY, "not a list".encodeToByteArray())
        assertEquals(null, repository(live()).lastTopCoins())
        assertEquals(null, store.get(CachePolicy.LastKnown, TOP_COINS_KEY))
    }

    /** Answers each route from its own queue. A route with no answer left gets 500, so an unexpected call fails the test. */
    private class Routes : Dispatcher() {
        private val queues = HashMap<String, ArrayDeque<MockResponse>>()
        private val counts = HashMap<String, Int>()
        private val keys = ArrayList<String?>()

        @Synchronized
        fun on(route: String, vararg answers: MockResponse) {
            queues.getOrPut(route) { ArrayDeque() }.addAll(answers)
        }

        @Synchronized
        fun calls(route: String): Int = counts[route] ?: 0

        @Synchronized
        fun total(): Int = counts.values.sum()

        /** The CoinGecko key header of each call to CoinGecko, in order. */
        @Synchronized
        fun keysSent(): List<String?> = keys.toList()

        @Synchronized
        override fun dispatch(request: RecordedRequest): MockResponse {
            val route = routeOf(request.target)
            counts[route] = calls(route) + 1
            if (route == "cg") keys.add(request.headers["x-cg-demo-api-key"])
            return queues[route]?.removeFirstOrNull() ?: MockResponse.Builder().code(500).build()
        }

        private fun routeOf(path: String): String = when {
            path.startsWith("/prices/current/") -> "prices"
            path.startsWith("/percentage/") -> "percentage"
            path.startsWith("/cg/api/v3/simple/price") -> "cg"
            else -> "other"
        }
    }

    @Test
    fun aDayChartIsKeptInTheVaultSoItIsReadWithNoSourceAsked() = runBlocking {
        val day = PriceSeries(listOf(PricePoint(1_000L, 2.0), PricePoint(2_000L, 2.5)))
        val source = FakeProvider(id = "defillama", networks = setOf(ETH), ops = setOf(Ops.PRICE_CHART))
        source.outcomes.add(day)
        assertEquals(day, repository(listOf(source)).chart(AssetId.Native(ETH), ChartFrame.H1, nowMs = 3_000L))

        val kept: Map<AssetId, PriceSeries> = repository(emptyList()).lastDayCharts(listOf(AssetId.Native(ETH)))
        assertEquals(mapOf<AssetId, PriceSeries>(AssetId.Native(ETH) to day), kept)
    }

    @Test
    fun aTokenIsPricedOnceAndKeptApartFromTheNativeCoinOfItsChain() = runBlocking {
        val usdc = AssetId.Erc20.of(ETH, "0xA0B86991C6218B36C1D19D4A2E9EB0CE3606EB48")
        val source = FakeProvider(id = "defillama", networks = setOf(ETH), ops = setOf(Ops.PRICE_TOKEN))
        source.outcomes.add(PriceQuote(usd = 0.9998, change24hPct = -0.01, asOfMs = 1_000L))
        val repository = repository(listOf(source))

        assertEquals(Price(usd = 0.9998, change24hPct = -0.01, asOfMs = 1_000L, stale = false), repository.assetPrices(listOf(usdc))[usdc])
        clock.now += 30_000L
        assertEquals(0.9998, repository.assetPrices(listOf(usdc, AssetId.Native(ETH)))[usdc]!!.usd, 0.0)
        assertEquals(1, source.calls)
    }

    @Test
    fun theFiatRatesAreAskedOnceAndKeptForTheTtlThenAskedAgain() = runBlocking {
        val source = FakeProvider(id = "coingecko", networks = setOf(MARKET_NET), ops = setOf(Ops.MARKET_FIAT))
        source.outcomes.add(FiatQuote(perUsd = mapOf("EUR" to 0.92), asOfMs = 1_000L))
        source.outcomes.add(FiatQuote(perUsd = mapOf("EUR" to 0.93), asOfMs = 2_000L))
        val repository = repository(listOf(source))

        assertEquals(Kept(FiatQuote(perUsd = mapOf("EUR" to 0.92), asOfMs = 1_000L), stale = false), repository.fiatRates())
        clock.now += 30_000L
        assertEquals(Kept(FiatQuote(perUsd = mapOf("EUR" to 0.92), asOfMs = 1_000L), stale = false), repository.fiatRates())
        assertEquals(1, source.calls)

        clock.now += 31_000L
        assertEquals(Kept(FiatQuote(perUsd = mapOf("EUR" to 0.93), asOfMs = 2_000L), stale = false), repository.fiatRates())
        assertEquals(2, source.calls)
    }

    @Test
    fun whenNoSourceAnswersTheLastFiatRatesKeptAreReturnedAsStale() = runBlocking {
        val source = FakeProvider(id = "coingecko", networks = setOf(MARKET_NET), ops = setOf(Ops.MARKET_FIAT))
        source.outcomes.add(FiatQuote(perUsd = mapOf("EUR" to 0.92), asOfMs = 1_000L))
        source.outcomes.add(ProviderError.Fatal("The provider is down."))
        val repository = repository(listOf(source))
        repository.fiatRates()
        clock.now += 10 * 60_000L

        assertEquals(Kept(FiatQuote(perUsd = mapOf("EUR" to 0.92), asOfMs = 1_000L), stale = true), repository.fiatRates())
    }

    @Test
    fun withNoSourceServingTheRatesNoRateIsReadAndNothingIsAsked() = runBlocking {
        val source = FakeProvider(id = "coingecko", networks = setOf(MARKET_NET), ops = setOf(Ops.MARKET_TRENDING))
        val repository = repository(listOf(source))

        assertNull(repository.fiatRates())
        assertEquals(0, source.calls)
    }
}
