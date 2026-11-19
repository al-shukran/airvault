package app.lumio.data.provider.blockscout

import app.lumio.data.ETH
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.Http
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.Op
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
 * The Blockscout plugin against a local MockWebServer that stands in for the Ethereum instance. The success answer is the one the live instance gave on
 * 2026-11-15, and the invalid-address answer is the one it gave to an invalid address. No test reaches a network.
 */
class BlockscoutProviderTest {
    private val server = MockWebServer()
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

    /** The plugin with its Ethereum instance set to the local server. */
    private fun provider() = BlockscoutProvider(bases = mapOf("1" to server.url("/").toString()))

    private fun <T> call(req: Request<T>, cfg: ProviderConfig = ProviderConfig()): T = runBlocking { provider().execute(req, cfg, http) }

    @Test
    fun theNativeBalanceIsWeiFromTheInstanceAccountApi() {
        server.enqueue(MockResponse().setBody("""{"message":"OK","result":"663046792267785498951364","status":"1"}"""))

        assertEquals(BigInteger("663046792267785498951364"), call(NativeBalance(ETH, ethAddress)))
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api", url.encodedPath)
        assertEquals("account", url.queryParameter("module"))
        assertEquals("balance", url.queryParameter("action"))
        assertEquals(ethAddress, url.queryParameter("address"))
    }

    @Test
    fun anInvalidAddressHashIsFatalSoAnotherProviderCanAnswer() {
        server.enqueue(MockResponse().setBody("""{"message":"Invalid address hash","result":null,"status":"0"}"""))

        assertFailsWith<ProviderError.Fatal> { call(NativeBalance(ETH, ethAddress)) }
    }

    @Test
    fun aRetryAfterHeaderIsTheCooldownInMilliseconds() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))

        assertEquals(30_000L, assertFailsWith<ProviderError.RateLimited> { call(NativeBalance(ETH, ethAddress)) }.retryAfterMs)
    }

    @Test
    fun aServerErrorIsTransient() {
        server.enqueue(MockResponse().setResponseCode(503))

        assertFailsWith<ProviderError.Transient> { call(NativeBalance(ETH, ethAddress)) }
    }

    @Test
    fun malformedAnswersAreTransientOrFatal() {
        server.enqueue(MockResponse().setBody("<html>portal</html>"))
        server.enqueue(MockResponse().setBody("""{"message":"OK","result":"1e18","status":"1"}"""))
        server.enqueue(MockResponse().setBody("""{"message":"OK"}"""))

        assertFailsWith<ProviderError.Transient> { call(NativeBalance(ETH, ethAddress)) }
        assertFailsWith<ProviderError.Fatal> { call(NativeBalance(ETH, ethAddress)) }
        assertFailsWith<ProviderError.Fatal> { call(NativeBalance(ETH, ethAddress)) }
    }

    @Test
    fun unhostedChainsAndTestnetsAreUnsupportedAndNeverCalled() {
        assertFailsWith<ProviderError.Unsupported> { call(NativeBalance(NetworkId(Networks.evm(8453), testnet = false), ethAddress)) }
        assertFailsWith<ProviderError.Unsupported> { call(NativeBalance(NetworkId(Networks.evm(1), testnet = true), ethAddress)) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theProductionInstanceServesEthereumMainnetNativeBalanceOnly() {
        val c = ProviderConfig()
        assertTrue(BlockscoutProvider().supports(ETH, Op.NATIVE_BALANCE, c))
        assertFalse(BlockscoutProvider().supports(NetworkId(Networks.evm(8453), testnet = false), Op.NATIVE_BALANCE, c))
        assertFalse(BlockscoutProvider().supports(ETH, Op.NONCE, c))
    }

    @Test
    fun aPlainHttpInstanceToAnotherHostIsNotSupported() {
        assertFalse(BlockscoutProvider(bases = mapOf("1" to "http://example.org/")).supports(ETH, Op.NATIVE_BALANCE, ProviderConfig()))
    }

    @Test
    fun theApiKeyGoesInTheApikeyQueryParameter() {
        server.enqueue(MockResponse().setBody("""{"message":"OK","result":"1","status":"1"}"""))

        call(NativeBalance(ETH, ethAddress), ProviderConfig(apiKey = "proapi_test"))

        val request = server.takeRequest()
        assertEquals("proapi_test", request.requestUrl!!.queryParameter("apikey"))
        assertNull(request.getHeader("apikey"))
    }

    @Test
    fun withoutAKeyNoApikeyParameterIsSent() {
        server.enqueue(MockResponse().setBody("""{"message":"OK","result":"1","status":"1"}"""))

        call(NativeBalance(ETH, ethAddress))

        assertNull(server.takeRequest().requestUrl!!.queryParameter("apikey"))
    }
}
