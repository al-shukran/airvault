package app.lumio.data.provider.routescan

import app.lumio.data.ETH
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.Http
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.OpKey
import app.lumio.data.provider.Ops
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The Routescan plugin against a local MockWebServer. The success answer has the shape that the live API gave on 2026-11-15. The others are written in
 * the shapes its docs and that probe show. No test reaches a network.
 */
class RoutescanProviderTest {
    private val server = MockWebServer()
    private val provider = RoutescanProvider()
    private val http = Http(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build())
    private val ethAddress = "0x" + "ab".repeat(20)

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun cfg(key: String? = null) = ProviderConfig(baseUrl = server.url("/").toString(), apiKey = key)

    private fun <T> call(req: Request<T>, cfg: ProviderConfig = cfg()): T = runBlocking { provider.execute(req, cfg, http) }

    @Test
    fun theNativeBalanceIsWeiFromTheEtherscanCompatiblePath() {
        server.enqueue(MockResponse.Builder().body("""{"status":"1","message":"OK","result":"4774551794456062094343"}""").build())

        assertEquals(BigInteger("4774551794456062094343"), call(NativeBalance(ETH, ethAddress)))
        val request = server.takeRequest()
        val url = request.url
        assertEquals("/v2/network/mainnet/evm/1/etherscan/api", url.encodedPath)
        assertEquals("account", url.queryParameter("module"))
        assertEquals("balance", url.queryParameter("action"))
        assertEquals(ethAddress, url.queryParameter("address"))
        assertEquals("latest", url.queryParameter("tag"))
        assertNull(request.headers["apikey"])
    }

    @Test
    fun aKeyGoesInTheApikeyHeaderAndNeverInTheUrl() {
        server.enqueue(MockResponse.Builder().body("""{"status":"1","message":"OK","result":"1"}""").build())

        call(NativeBalance(ETH, ethAddress), cfg(key = "KEY-123"))

        val request = server.takeRequest()
        assertEquals("KEY-123", request.headers["apikey"])
        assertNull(request.url.queryParameter("apikey"))
    }

    @Test
    fun aRetryAfterHeaderIsTheCooldownInMilliseconds() {
        server.enqueue(MockResponse.Builder().code(429).setHeader("Retry-After", "30").build())

        assertEquals(30_000L, assertFailsWith<ProviderError.RateLimited> { call(NativeBalance(ETH, ethAddress)) }.retryAfterMs)
    }

    @Test
    fun aRateLimitAnsweredInTheBodyIsCooledWithNoFixedWait() {
        server.enqueue(
            MockResponse.Builder().body(
                """{"status":"0","message":"NOTOK","result":"Max rate limit reached, please use API Key for higher rate limit"}""",
            ).build(),
        )

        assertNull(assertFailsWith<ProviderError.RateLimited> { call(NativeBalance(ETH, ethAddress)) }.retryAfterMs)
    }

    @Test
    fun aChainTheApiAnswersAsNotSupportedIsUnsupportedNotAFailure() {
        server.enqueue(MockResponse.Builder().body("""{"status":"0","message":"chain not supported","result":null}""").build())

        assertFailsWith<ProviderError.Unsupported> { call(NativeBalance(ETH, ethAddress)) }
    }

    @Test
    fun aChainOutsideTheProbedListAndATestnetAreUnsupportedAndNeverCalled() {
        assertFailsWith<ProviderError.Unsupported> { call(NativeBalance(NetworkId(Networks.evm(8453), testnet = false), ethAddress)) }
        assertFailsWith<ProviderError.Unsupported> { call(NativeBalance(NetworkId(Networks.evm(1), testnet = true), ethAddress)) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aRefusedQueryIsFatalAndTheMessageHoldsNoKey() {
        server.enqueue(MockResponse.Builder().body("""{"status":"0","message":"NOTOK","result":"Invalid querystring request"}""").build())

        val e = assertFailsWith<ProviderError.Fatal> { call(NativeBalance(ETH, ethAddress), cfg(key = "KEY-123")) }
        assertFalse(e.message.orEmpty().contains("KEY-123"))
    }

    @Test
    fun aServerErrorIsTransient() {
        server.enqueue(MockResponse.Builder().code(503).build())

        assertFailsWith<ProviderError.Transient> { call(NativeBalance(ETH, ethAddress)) }
    }

    @Test
    fun malformedAnswersAreTransientOrFatal() {
        server.enqueue(MockResponse.Builder().body("<html>portal</html>").build())
        server.enqueue(MockResponse.Builder().body("""{"status":"1","message":"OK","result":"1.5"}""").build())
        server.enqueue(MockResponse.Builder().body("""{"message":"OK"}""").build())

        assertFailsWith<ProviderError.Transient> { call(NativeBalance(ETH, ethAddress)) }
        assertFailsWith<ProviderError.Fatal> { call(NativeBalance(ETH, ethAddress)) }
        assertFailsWith<ProviderError.Fatal> { call(NativeBalance(ETH, ethAddress)) }
    }

    @Test
    fun aMalformedAddressIsNotFoundAndIsNeverSent() {
        assertFailsWith<ProviderError.NotFound> { call(NativeBalance(ETH, "../../etc")) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theSupportTableHoldsTheProbedMainnetsAndTheNativeBalanceOnly() {
        val c = cfg()
        assertTrue(provider.supports(ETH, OpKey.on(ETH, "nativeBalance"), c))
        assertTrue(provider.supports(NetworkId(Networks.evm(5000), testnet = false), OpKey.on(NetworkId(Networks.evm(5000), testnet = false), "nativeBalance"), c))
        assertFalse(provider.supports(NetworkId(Networks.evm(8453), testnet = false), OpKey.on(NetworkId(Networks.evm(8453), testnet = false), "nativeBalance"), c))
        assertFalse(provider.supports(ETH, Ops.EVM_NONCE, c))
        assertFalse(provider.supports(NetworkId(Networks.BTC_MAINNET, testnet = false), OpKey.on(NetworkId(Networks.BTC_MAINNET, testnet = false), "nativeBalance"), c))
    }

    @Test
    fun aPlainHttpBaseToAnotherHostIsNotSupported() {
        assertFalse(provider.supports(ETH, OpKey.on(ETH, "nativeBalance"), ProviderConfig(baseUrl = "http://example.org/")))
    }

    @Test
    fun theKeyedPlanHasItsOwnLimits() {
        assertTrue(provider.defaultLimits(cfg(key = "KEY-123")).rps > provider.defaultLimits(cfg()).rps)
    }
}
