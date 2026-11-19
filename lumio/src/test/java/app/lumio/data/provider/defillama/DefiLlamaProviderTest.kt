package app.lumio.data.provider.defillama

import app.lumio.data.ETH
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.Http
import app.lumio.data.provider.NativePrice
import app.lumio.data.provider.OpKey
import app.lumio.data.provider.Ops
import app.lumio.data.provider.PriceQuote
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.TokenPrice
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
        assertFailsWith<ProviderError.Unsupported> { call(NativePrice(NetworkId(Networks.evm(1), testnet = true))) }
        assertFailsWith<ProviderError.Unsupported> { call(NativePrice(NetworkId(Networks.evm(250), testnet = false))) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theProviderIsKeylessPricesOnlyAndNeverPlainHttpToAnotherHost() {
        val provider = DefiLlamaProvider()
        assertTrue(Trait.KEYLESS in provider.traits)
        assertTrue(provider.supports(ETH, Ops.PRICE_NATIVE, ProviderConfig()))
        assertFalse(provider.supports(ETH, OpKey.on(ETH, "nativeBalance"), ProviderConfig()))
        assertFalse(provider.supports(ETH, Ops.PRICE_NATIVE, ProviderConfig(baseUrl = "http://example.org/")))
    }

    @Test
    fun aTokenIsPricedByItsChainPrefixAndContractAndItsChangeIsAskedForToo() {
        val usdc = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
        val coin = "ethereum:$usdc"
        server.enqueue(MockResponse().setBody("""{"coins":{"$coin":{"price":0.9998,"symbol":"USDC","timestamp":1794774850,"confidence":0.999}}}"""))
        server.enqueue(MockResponse().setBody("""{"coins":{"$coin":-0.01}}"""))

        assertEquals(PriceQuote(usd = 0.9998, change24hPct = -0.01, asOfMs = 1_794_774_850_000L), call(TokenPrice(ETH, usdc)))
        assertEquals("/prices/current/$coin", server.takeRequest().path)
        assertEquals("/percentage/$coin?period=24h", server.takeRequest().path)
    }

    @Test
    fun aTokenOnAChainWithoutAPrefixOrOnATestnetIsUnsupportedWithoutACall() {
        val usdc = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
        assertFailsWith<ProviderError.Unsupported> { call(TokenPrice(NetworkId(Networks.evm(250), testnet = false), usdc)) }
        assertFailsWith<ProviderError.Unsupported> { call(TokenPrice(NetworkId(Networks.evm(1), testnet = true), usdc)) }
        assertEquals(0, server.requestCount)
        assertTrue(DefiLlamaProvider().supports(ETH, Ops.PRICE_TOKEN, ProviderConfig()))
    }
}
