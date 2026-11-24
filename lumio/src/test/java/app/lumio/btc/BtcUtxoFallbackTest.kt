package app.lumio.btc

import app.lumio.data.BTC
import app.lumio.data.MonotonicClock
import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.esplora.BlockstreamProvider
import app.lumio.data.esplora.MempoolProvider
import app.lumio.data.provider.AddressUtxos
import app.lumio.data.provider.Http
import app.lumio.data.provider.blockchair.BlockchairProvider
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
 * The Bitcoin scheduler with Blockchair listed last, built the way LumioApp builds it. Three local MockWebServers stand in for mempool.space,
 * blockstream.info and Blockchair, so nothing reaches the network.
 */
class BtcUtxoFallbackTest {
    private val mempool = MockWebServer()
    private val blockstream = MockWebServer()
    private val blockchair = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = Http(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build())

    /** A BIP-173 example address, which is public. */
    private val address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"

    private val oneOutput = """{"block_id":800000,"transaction_hash":"${"ab".repeat(32)}","index":0,"value":5000}"""

    private fun blockchairPage(rows: String) = """{"data":{"$address":{"utxo":[$rows]}},"context":{"code":200}}"""

    @Before
    fun start() {
        mempool.start()
        blockstream.start()
        blockchair.start()
    }

    @After
    fun stop() {
        scope.cancel()
        mempool.close()
        blockstream.close()
        blockchair.close()
    }

    private fun scheduler(): Scheduler = Scheduler(
        providers = listOf(
            MempoolProvider(bases = mapOf("mainnet" to mempool.url("/api").toString())),
            BlockstreamProvider(bases = mapOf("mainnet" to blockstream.url("/api").toString())),
            BlockchairProvider(fallbackOnly = true),
        ),
        configs = {
            mapOf(
                "mempool" to ProviderConfig(priority = 0),
                "blockstream" to ProviderConfig(priority = 1),
                // A fast bucket, so the second page does not wait for its token. The page charge is tested in PageChargeTest.
                "blockchair" to ProviderConfig(
                    baseUrl = blockchair.url("/").toString(),
                    apiKey = "fake-key",
                    limits = Limits(rps = 100.0, burst = 10, maxConcurrent = 1),
                ),
            )
        },
        http = http,
        clock = MonotonicClock,
        random = Random(7),
        scope = scope,
    )

    @Test
    fun blockchairServesTheOutputsWhenMempoolAndBlockstreamAreCoolingDown() = runBlocking {
        mempool.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "60").build())
        blockstream.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "60").build())
        blockchair.enqueue(MockResponse.Builder().body(blockchairPage(oneOutput)).build())
        blockchair.enqueue(MockResponse.Builder().body(blockchairPage("")).build())
        val scheduler = scheduler()

        assertEquals(1, scheduler.run(AddressUtxos(BTC, address), Priority.FOREGROUND).size)

        // Both Esplora providers are cooling now, so the next read goes straight to Blockchair.
        blockchair.enqueue(MockResponse.Builder().body(blockchairPage(oneOutput)).build())
        blockchair.enqueue(MockResponse.Builder().body(blockchairPage("")).build())
        assertEquals(1, scheduler.run(AddressUtxos(BTC, address), Priority.FOREGROUND).size)

        assertEquals(1, mempool.requestCount)
        assertEquals(1, blockstream.requestCount)
        // Two pages per read: the answer with one output, then the empty page that ends the list.
        assertEquals(4, blockchair.requestCount)
    }

    @Test
    fun blockchairIsNotCalledWhileMempoolIsHealthy() = runBlocking {
        mempool.enqueue(MockResponse.Builder().body("[]").build())

        assertEquals(0, scheduler().run(AddressUtxos(BTC, address), Priority.FOREGROUND).size)

        assertEquals(1, mempool.requestCount)
        assertEquals(0, blockchair.requestCount)
    }
}
