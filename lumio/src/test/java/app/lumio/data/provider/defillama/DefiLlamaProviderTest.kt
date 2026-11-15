package app.lumio.data.provider.defillama

import app.lumio.data.ETH
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.Http
import app.lumio.data.provider.NativePrice
import app.lumio.data.provider.Op
import app.lumio.data.provider.PriceQuote
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.Trait
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The DefiLlama price plugin against a local MockWebServer. The answers are the shapes of the documented endpoints, as the source gave them on
 * 2026-11-16. No test reaches a network.
 */
class DefiLlamaProviderTest {
    private val server = MockWebServer()
    private val http = Http(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build())

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun cfg() = ProviderConfig(baseUrl = server.url("/").toString())

    private fun <T> call(req: Request<T>): T = runBlocking { DefiLlamaProvider().execute(req, cfg(), http) }

    private fun priceAnswer(price: String = "1831.253676", timestamp: Long = 1794774850L) = MockResponse().setBody(
        """{"coins":{"coingecko:ethereum":{"price":$price,"symbol":"ETH","timestamp":$timestamp,"confidence":0.999}}}""",
    )

    private fun changeAnswer(value: String) = MockResponse().setBody("""{"coins":{"coingecko:ethereum":$value}}""")

    @Test
    fun thePriceAndItsTwentyFourHourChangeComeFromTwoRequests() {
        server.enqueue(priceAnswer())
        server.enqueue(changeAnswer("0.58"))

        assertEquals(PriceQuote(usd = 1831.253676, change24hPct = 0.58, asOfMs = 1_794_774_850_000L), call(NativePrice(ETH)))
        assertEquals("/prices/current/coingecko:ethereum", server.takeRequest().path)
        assertEquals("/percentage/coingecko:ethereum?period=24h", server.takeRequest().path)
    }

    @Test
    fun aNullOrMissingChangeIsNoChangeAndNotAFailure() {
        server.enqueue(priceAnswer())
        server.enqueue(changeAnswer("null"))
        assertNull(call(NativePrice(ETH)).change24hPct)

        server.enqueue(priceAnswer())
        server.enqueue(MockResponse().setBody("""{"coins":{}}"""))
        assertNull(call(NativePrice(ETH)).change24hPct)
    }

    @Test
    fun aCoinTheSourceDoesNotPriceIsUnsupportedAndTheChangeIsNotAsked() {
        server.enqueue(MockResponse().setBody("""{"coins":{}}"""))

        assertFailsWith<ProviderError.Unsupported> { call(NativePrice(ETH)) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aRetryAfterHeaderIsTheRateLimitCooldownInMilliseconds() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))

        assertEquals(30_000L, assertFailsWith<ProviderError.RateLimited> { call(NativePrice(ETH)) }.retryAfterMs)
    }

    @Test
    fun aRateLimitOnTheChangeRequestFailsTheWholeCall() {
        server.enqueue(priceAnswer())
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "45"))

        assertEquals(45_000L, assertFailsWith<ProviderError.RateLimited> { call(NativePrice(ETH)) }.retryAfterMs)
    }

    @Test
    fun aServerErrorIsTransient() {
        server.enqueue(MockResponse().setResponseCode(503))

        assertFailsWith<ProviderError.Transient> { call(NativePrice(ETH)) }
    }

    @Test
    fun aPriceThatIsZeroOrIsWrittenAsAStringIsRefused() {
        server.enqueue(priceAnswer(price = "0"))
        assertFailsWith<ProviderError.Fatal> { call(NativePrice(ETH)) }

        server.enqueue(priceAnswer(price = "\"1831.25\""))
        assertFailsWith<ProviderError.Fatal> { call(NativePrice(ETH)) }
    }

    @Test
    fun aTestnetOrAnUnpricedNetworkIsUnsupportedAndNeverCalled() {
        assertFailsWith<ProviderError.Unsupported> { call(NativePrice(NetworkId(Family.EVM, "1", testnet = true))) }
        assertFailsWith<ProviderError.Unsupported> { call(NativePrice(NetworkId(Family.EVM, "250", testnet = false))) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theProviderIsKeylessPricesOnlyAndNeverPlainHttpToAnotherHost() {
        val provider = DefiLlamaProvider()
        assertTrue(Trait.KEYLESS in provider.traits)
        assertTrue(provider.supports(ETH, Op.PRICE, ProviderConfig()))
        assertFalse(provider.supports(ETH, Op.NATIVE_BALANCE, ProviderConfig()))
        assertFalse(provider.supports(ETH, Op.PRICE, ProviderConfig(baseUrl = "http://example.org/")))
    }
}
