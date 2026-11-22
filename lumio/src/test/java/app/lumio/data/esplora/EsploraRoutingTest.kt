package app.lumio.data.esplora

import app.lumio.data.MonotonicClock
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.Http
import app.lumio.data.provider.TipHeight
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
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
 * The Esplora providers through the scheduler, over two local MockWebServers that stand in for mempool.space and blockstream.info. Mempool is
 * the first choice, so each test's first call goes to it. Nothing reaches the network.
 */
class EsploraRoutingTest {
    private val mempool = MockWebServer()
    private val blockstream = MockWebServer()
    private val testnet4 = NetworkId(Networks.BTC_TESTNET4, testnet = true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A short read timeout, so a server that stalls times out within the test. */
    private val http = Http(
        OkHttpClient.Builder()
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build(),
    )

    @Before
    fun start() {
        mempool.start()
        blockstream.start()
    }

    @After
    fun stop() {
        scope.cancel()
        mempool.close()
        blockstream.close()
    }

    private fun scheduler(): Scheduler = Scheduler(
        providers = listOf(
            MempoolProvider(bases = mapOf("testnet4" to mempool.url("/api").toString())),
            BlockstreamProvider(bases = mapOf("testnet4" to blockstream.url("/api").toString())),
        ),
        configs = {
            mapOf(
                "mempool" to ProviderConfig(priority = 0),
                "blockstream" to ProviderConfig(priority = 1),
            )
        },
        http = http,
        clock = MonotonicClock,
        random = Random(7),
        scope = scope,
    )

    @Test
    fun aStalledProviderTimesOutAndTheCallMovesToTheNextOne() = runBlocking {
        // The body arrives after the read timeout, so mempool's read fails as a timeout and is retried on blockstream.
        mempool.enqueue(MockResponse.Builder().body("850002\n").bodyDelay(2, TimeUnit.SECONDS).build())
        blockstream.enqueue(MockResponse.Builder().body("850002\n").build())

        assertEquals(850_002L, scheduler().run(TipHeight(testnet4), Priority.FOREGROUND))

        assertEquals(1, mempool.requestCount)
        assertEquals(1, blockstream.requestCount)
    }

    @Test
    fun aRateLimitedProviderCoolsDownAndTheNextCallGoesElsewhere() = runBlocking {
        mempool.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "60").build())
        blockstream.enqueue(MockResponse.Builder().body("850003\n").build())
        blockstream.enqueue(MockResponse.Builder().body("850003\n").build())
        val scheduler = scheduler()

        assertEquals(850_003L, scheduler.run(TipHeight(testnet4), Priority.FOREGROUND))
        assertEquals(850_003L, scheduler.run(TipHeight(testnet4), Priority.FOREGROUND))

        // Mempool asked for 60 seconds, so the second call never reached it.
        assertEquals(1, mempool.requestCount)
        assertEquals(2, blockstream.requestCount)
    }
}
