package app.lumio.data.price

import app.lumio.data.ETH
import app.lumio.data.FakeClock
import app.lumio.data.config.ProviderConfig
import app.lumio.data.provider.Http
import app.lumio.data.provider.coingecko.CoinGeckoProvider
import app.lumio.data.sched.Scheduler
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The logo cache over a real scheduler and the CoinGecko plugin, against a local MockWebServer. */
class TokenLogosTest {
    private val server = MockWebServer()

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun aTokenWithNoLogoAtTheSourceIsAskedOnceAndIsNoHealthError() {
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":"Coin not found"}""").build())

        runBlocking {
            val scheduler = Scheduler(
                providers = listOf(CoinGeckoProvider()),
                configs = { mapOf("coingecko" to ProviderConfig(baseUrl = server.url("/api/v3/").toString())) },
                http = Http(OkHttpClient()),
                clock = FakeClock(1_000L),
                random = Random(7),
                scope = this,
            )
            val logos = TokenLogos(scheduler)

            assertNull(logos.imageOf(ETH, USDC))
            assertNull(logos.imageOf(ETH, USDC))
            assertEquals(1, server.requestCount)
            // A not-found answer is served, not failed: the health score has no error penalty.
            assertEquals(0.0, scheduler.statusOf("coingecko")?.score)
        }
    }

    private companion object {
        const val USDC = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    }
}
