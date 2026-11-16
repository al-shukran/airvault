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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
        server.shutdown()
    }

    /** A repository whose only price provider is DefiLlama, on the local server. */
    private fun repository(): PriceRepository {
        val configs: Map<String, ProviderConfig> = ProviderCatalog.withDefaults(emptyMap()).mapValues { (id, cfg) ->
            cfg.copy(baseUrl = if (id == "defillama") server.url("/").toString() else cfg.baseUrl, limits = fast)
        }
        val scheduler = Scheduler(listOf(DefiLlamaProvider()), { configs }, http, clock, Random(7), scope)
        return PriceRepository(scheduler) { null }
    }

    private fun answer(vararg points: String): MockResponse = MockResponse().setBody(
        """{"coins":{"coingecko:ethereum":{"symbol":"ETH","confidence":0.99,"prices":[${points.joinToString(",")}]}}}""",
    )

    private fun point(timestamp: Long, price: String) = """{"timestamp":$timestamp,"price":$price}"""

    private fun chart(repository: PriceRepository, range: ChartRange): PriceSeries? = runBlocking {
        repository.chart(AssetId.Native(ETH), range, nowMs = now)
    }

    @Test
    fun aRangeIsAskedOnceAndThenKeptForTheSession() {
        server.enqueue(answer(point(1_799_998_800L, "1840.5")))
        val repository = repository()

        val first = chart(repository, ChartRange.H24)
        val second = chart(repository, ChartRange.H24)

        assertEquals(1, server.requestCount)
        assertEquals(first, second)
        assertEquals(1, assertNotNull(first).points.size)
    }

    @Test
    fun aDifferentRangeIsAskedForItsOwnWindow() {
        server.enqueue(answer(point(1_799_998_800L, "1840.5")))
        server.enqueue(answer(point(1_799_998_800L, "1841.0")))
        val repository = repository()

        chart(repository, ChartRange.H24)
        chart(repository, ChartRange.D7)
        chart(repository, ChartRange.H24)

        assertEquals(2, server.requestCount)
        assertEquals("/chart/coingecko:ethereum?start=1799913600&span=25&period=1h", server.takeRequest().path)
        assertEquals("/chart/coingecko:ethereum?start=1799395200&span=43&period=4h", server.takeRequest().path)
    }

    @Test
    fun anEmptyWindowIsAnAnswerThatIsNotKeptSoItIsAskedAgain() {
        server.enqueue(answer())
        server.enqueue(answer(point(1_799_998_800L, "1840.5")))
        val repository = repository()

        assertEquals(0, assertNotNull(chart(repository, ChartRange.H24)).points.size)
        assertEquals(1, assertNotNull(chart(repository, ChartRange.H24)).points.size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aSourceThatPricesNoneOfTheCoinGivesNoChart() {
        server.enqueue(MockResponse().setBody("""{"coins":{}}"""))

        assertNull(chart(repository(), ChartRange.H24))
    }
}
