package app.lumio.data.price

import app.lumio.data.ETH
import app.lumio.data.FakeClock
import app.lumio.data.asset.AssetId
import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.provider.Http
import app.lumio.data.provider.ProviderCatalog
import app.lumio.data.provider.defillama.DefiLlamaProvider
import app.lumio.data.sched.Scheduler
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The price repository's charts over the real scheduler, with DefiLlama on a local MockWebServer. A series with points is kept in memory for the
 * session, so a range read again is not asked again. An empty window and a source with no price are not kept. The window is the one the caller
 * gives as the time to end at.
 */
class PriceChartRepositoryTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = Http(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build())
    private val clock = FakeClock(1_000_000L)
    private val fast = Limits(rps = 1_000.0, burst = 1_000, maxConcurrent = 10)
    private val now = 1_800_000_000_000L

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    /** A repository whose only price provider is DefiLlama, on the local server. */
    private fun repository(): PriceRepository {
        val configs: Map<String, ProviderConfig> = ProviderCatalog.withDefaults(emptyMap()).mapValues { (id, cfg) ->
            cfg.copy(baseUrl = if (id == "defillama") server.url("/").toString() else cfg.baseUrl, limits = fast)
        }
        val scheduler = Scheduler(listOf(DefiLlamaProvider()), { configs }, http, clock, Random(7), scope)
        return PriceRepository(scheduler) { null }
    }

    private fun answer(vararg points: String): MockResponse = MockResponse.Builder().body(
        """{"coins":{"coingecko:ethereum":{"symbol":"ETH","confidence":0.99,"prices":[${points.joinToString(",")}]}}}""",
    ).build()

    private fun point(timestamp: Long, price: String) = """{"timestamp":$timestamp,"price":$price}"""

    private fun chart(repository: PriceRepository, range: ChartRange): PriceSeries? = runBlocking {
        repository.chart(AssetId.Native(ETH), range, nowMs = now)
    }

    @Test
    fun aRangeIsAskedOnceAndThenKeptForTheSession() {
        server.enqueue(answer(point(1_799_998_800L, "1840.5")))
        val repository = repository()

        val first = chart(repository, ChartRange.D1)
        val second = chart(repository, ChartRange.D1)

        assertEquals(1, server.requestCount)
        assertEquals(first, second)
        assertEquals(1, assertNotNull(first).points.size)
    }

    @Test
    fun aKeptChartIsReadAgainOnceItIsOlderThanTheTtl() {
        server.enqueue(answer(point(1_799_998_800L, "1840.5")))
        server.enqueue(answer(point(1_799_998_800L, "1841.0")))
        val repository = repository()

        val first = runBlocking { repository.chart(AssetId.Native(ETH), ChartRange.D1, nowMs = now) }
        val justBefore = runBlocking { repository.chart(AssetId.Native(ETH), ChartRange.D1, nowMs = now + CHART_TTL_MS - 1) }
        val afterTtl = runBlocking { repository.chart(AssetId.Native(ETH), ChartRange.D1, nowMs = now + CHART_TTL_MS) }

        assertEquals(1_840.5, assertNotNull(first).points.single().usd)
        assertEquals(first, justBefore)
        assertEquals(1_841.0, assertNotNull(afterTtl).points.single().usd)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aDifferentRangeIsAskedForItsOwnWindow() {
        server.enqueue(answer(point(1_799_998_800L, "1840.5")))
        server.enqueue(answer(point(1_799_998_800L, "1841.0")))
        val repository = repository()

        chart(repository, ChartRange.D1)
        chart(repository, ChartRange.D7)
        chart(repository, ChartRange.D1)

        assertEquals(2, server.requestCount)
        assertEquals("/chart/coingecko:ethereum?start=1799913600&span=25&period=1h", server.takeRequest().target)
        assertEquals("/chart/coingecko:ethereum?start=1799395200&span=43&period=4h", server.takeRequest().target)
    }

    @Test
    fun anEmptyWindowIsAnAnswerThatIsNotKeptSoItIsAskedAgain() {
        server.enqueue(answer())
        server.enqueue(answer(point(1_799_998_800L, "1840.5")))
        val repository = repository()

        assertEquals(0, assertNotNull(chart(repository, ChartRange.D1)).points.size)
        assertEquals(1, assertNotNull(chart(repository, ChartRange.D1)).points.size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aSourceThatPricesNoneOfTheCoinGivesNoChart() {
        server.enqueue(MockResponse.Builder().body("""{"coins":{}}""").build())

        assertNull(chart(repository(), ChartRange.D1))
    }
}
