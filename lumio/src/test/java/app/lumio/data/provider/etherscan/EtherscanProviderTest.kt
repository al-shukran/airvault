package app.lumio.data.provider.etherscan

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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The Etherscan V2 plugin against a local MockWebServer. The success answer has the documented shape. The error answers are the texts that the docs
 * and the probe of 2026-11-15 give. No test reaches a network.
 */
class EtherscanProviderTest {
    private val server = MockWebServer()
    private val provider = EtherscanProvider()
    private val http = Http(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build())
    private val ethAddress = "0x" + "ab".repeat(20)

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun cfg(key: String? = "KEY-123") = ProviderConfig(baseUrl = server.url("/").toString(), apiKey = key)

    private fun <T> call(req: Request<T>, cfg: ProviderConfig = cfg()): T = runBlocking { provider.execute(req, cfg, http) }

    @Test
    fun withoutAKeyTheProviderServesNothingAndIsNeverCalled() {
        assertFalse(provider.supports(ETH, OpKey.on(ETH, "nativeBalance"), cfg(key = null)))
        assertFailsWith<ProviderError.Unsupported> { call(NativeBalance(ETH, ethAddress), cfg(key = null)) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theBalanceIsReadWithTheChainIdAndTheKeyInTheQuery() {
        server.enqueue(MockResponse().setBody("""{"status":"1","message":"OK","result":"172774397764084972158218"}"""))

        assertEquals(BigInteger("172774397764084972158218"), call(NativeBalance(ETH, ethAddress)))
        val url = server.takeRequest().requestUrl!!
        assertEquals("/v2/api", url.encodedPath)
        assertEquals("1", url.queryParameter("chainid"))
        assertEquals("account", url.queryParameter("module"))
        assertEquals("balance", url.queryParameter("action"))
        assertEquals(ethAddress, url.queryParameter("address"))
        assertEquals("latest", url.queryParameter("tag"))
        assertEquals("KEY-123", url.queryParameter("apikey"))
    }

    @Test
    fun theChainIdFollowsTheNetwork() {
        server.enqueue(MockResponse().setBody("""{"status":"1","message":"OK","result":"0"}"""))

        call(NativeBalance(NetworkId(Networks.evm(137), testnet = false), ethAddress))

        assertEquals("137", server.takeRequest().requestUrl!!.queryParameter("chainid"))
    }

    @Test
    fun aPaidOnlyChainIsNotServedEvenWithAKey() {
        val base = NetworkId(Networks.evm(8453), testnet = false)

        assertFalse(provider.supports(base, OpKey.on(base, "nativeBalance"), cfg()))
        assertFailsWith<ProviderError.Unsupported> { call(NativeBalance(base, ethAddress)) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aRateLimitAnsweredInTheBodyIsCooledWithNoFixedWait() {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"0","message":"NOTOK","result":"Max rate limit reached, please use API Key for higher rate limit"}""",
            ),
        )

        assertNull(assertFailsWith<ProviderError.RateLimited> { call(NativeBalance(ETH, ethAddress)) }.retryAfterMs)
    }

    @Test
    fun aQuotaAndTooManyInvalidKeyAttemptsAreRateLimits() {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"0","message":"NOTOK","result":"Community Free API limit reached. Resets 2026-06-24 12:00:00 UTC."}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"status":"0","message":"NOTOK","result":"Too many invalid api key attempts, please try again later"}""",
            ),
        )

        assertFailsWith<ProviderError.RateLimited> { call(NativeBalance(ETH, ethAddress)) }
        assertFailsWith<ProviderError.RateLimited> { call(NativeBalance(ETH, ethAddress)) }
    }

    @Test
    fun aRetryAfterHeaderIsTheCooldownInMilliseconds() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))

        assertEquals(30_000L, assertFailsWith<ProviderError.RateLimited> { call(NativeBalance(ETH, ethAddress)) }.retryAfterMs)
    }

    @Test
    fun aRejectedKeyIsFatalAndTheMessageHoldsNoKey() {
        server.enqueue(MockResponse().setBody("""{"status":"0","message":"NOTOK","result":"Invalid API Key (#err2)"}"""))

        val e = assertFailsWith<ProviderError.Fatal> { call(NativeBalance(ETH, ethAddress)) }
        assertFalse(e.message.orEmpty().contains("KEY-123"))
    }

    @Test
    fun aPlanThatDoesNotCoverTheChainIsUnsupportedNotAFailure() {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"0","message":"NOTOK","result":"Free API access is not supported for this chain. """ +
                    """Please upgrade your api plan for full chain coverage."}""",
            ),
        )

        assertFailsWith<ProviderError.Unsupported> { call(NativeBalance(ETH, ethAddress)) }
    }

    @Test
    fun malformedAnswersAreTransientOrFatal() {
        server.enqueue(MockResponse().setBody("<html>portal</html>"))
        server.enqueue(MockResponse().setBody("""{"status":"1","message":"OK","result":"1e18"}"""))
        server.enqueue(MockResponse().setBody("""{"message":"OK"}"""))

        assertFailsWith<ProviderError.Transient> { call(NativeBalance(ETH, ethAddress)) }
        assertFailsWith<ProviderError.Fatal> { call(NativeBalance(ETH, ethAddress)) }
        assertFailsWith<ProviderError.Fatal> { call(NativeBalance(ETH, ethAddress)) }
    }

    @Test
    fun theSupportTableNeedsAKeyAndAFreeTierMainnetForTheNativeBalance() {
        assertTrue(provider.supports(ETH, OpKey.on(ETH, "nativeBalance"), cfg()))
        assertFalse(provider.supports(ETH, OpKey.on(ETH, "nativeBalance"), cfg(key = null)))
        assertFalse(provider.supports(ETH, Ops.EVM_NONCE, cfg()))
        assertFalse(provider.supports(NetworkId(Networks.evm(1), testnet = true), OpKey.on(NetworkId(Networks.evm(1), testnet = true), "nativeBalance"), cfg()))
        assertFalse(provider.supports(NetworkId(Networks.BTC_MAINNET, testnet = false), OpKey.on(NetworkId(Networks.BTC_MAINNET, testnet = false), "nativeBalance"), cfg()))
    }

    @Test
    fun aPlainHttpBaseToAnotherHostIsNotSupported() {
        assertFalse(provider.supports(ETH, OpKey.on(ETH, "nativeBalance"), ProviderConfig(baseUrl = "http://example.org/", apiKey = "KEY-123")))
    }
}
