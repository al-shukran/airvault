package app.lumio.data.provider.coingecko

import app.lumio.data.BTC
import app.lumio.data.ETH
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.Http
import app.lumio.data.provider.NativePrice
import app.lumio.data.provider.Op
import app.lumio.data.provider.PriceQuote
import app.lumio.data.provider.ProviderError
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
 * The CoinGecko price plugin against a local MockWebServer. The answer is the shape of the simple-price endpoint, as the source gave it on 2026-11-16.
 * No test reaches a network, and no key is sent anywhere but the header.
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
        server.shutdown()
    }

    private fun cfg(key: String? = null) = ProviderConfig(baseUrl = server.url("/api/v3/").toString(), apiKey = key)

    private fun call(req: NativePrice, key: String? = null): PriceQuote = runBlocking { CoinGeckoProvider().execute(req, cfg(key), http) }

    private fun ethereumAnswer(change: String = "0.4118") = MockResponse().setBody(
        """{"ethereum":{"usd":1827.5,"usd_24h_change":$change,"last_updated_at":1794774853}}""",
    )

    @Test
    fun thePriceChangeAndTimeComeFromOneSimplePriceRequest() {
        server.enqueue(ethereumAnswer())

        assertEquals(PriceQuote(usd = 1827.5, change24hPct = 0.4118, asOfMs = 1_794_774_853_000L), call(NativePrice(ETH)))
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/v3/simple/price", url.encodedPath)
        assertEquals("ethereum", url.queryParameter("ids"))
        assertEquals("usd", url.queryParameter("vs_currencies"))
        assertEquals("true", url.queryParameter("include_24hr_change"))
        assertEquals("true", url.queryParameter("include_last_updated_at"))
    }

    @Test
    fun withoutAKeyNoKeyHeaderIsSent() {
        server.enqueue(ethereumAnswer())

        call(NativePrice(ETH))
        assertNull(server.takeRequest().getHeader("x-cg-demo-api-key"))
    }

    @Test
    fun aDemoKeyGoesInTheHeaderAndNeverInTheUrl() {
        server.enqueue(ethereumAnswer())

        call(NativePrice(ETH), key = "DEMO-KEY-123")
        val request = server.takeRequest()
        assertEquals("DEMO-KEY-123", request.getHeader("x-cg-demo-api-key"))
        assertFalse(request.requestUrl.toString().contains("DEMO-KEY-123"))
    }

    @Test
    fun aNullChangeIsNoChange() {
        server.enqueue(ethereumAnswer(change = "null"))

        assertNull(call(NativePrice(ETH)).change24hPct)
    }

    @Test
    fun aCoinTheSourceDoesNotKnowIsUnsupported() {
        server.enqueue(MockResponse().setBody("{}"))

        assertFailsWith<ProviderError.Unsupported> { call(NativePrice(ETH)) }
    }

    @Test
    fun aRateLimitWithoutRetryAfterLeavesTheCooldownToTheScheduler() {
        server.enqueue(MockResponse().setResponseCode(429))

        assertNull(assertFailsWith<ProviderError.RateLimited> { call(NativePrice(BTC)) }.retryAfterMs)
    }

    @Test
    fun aTestnetIsUnsupportedAndNeverCalled() {
        assertFailsWith<ProviderError.Unsupported> { call(NativePrice(NetworkId(Networks.evm(1), testnet = true))) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theProviderNeedsItsKeyAndIsNotAFallback() {
        val provider = CoinGeckoProvider()
        assertEquals(setOf(Trait.KEY_REQUIRED), provider.traits)
        assertTrue(provider.supports(BTC, Op.PRICE, ProviderConfig()))
        assertFalse(provider.supports(BTC, Op.NATIVE_BALANCE, ProviderConfig()))
        assertFalse(provider.defaultLimits(ProviderConfig()) == provider.defaultLimits(ProviderConfig(apiKey = "k")))
    }
}
