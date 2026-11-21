package app.lumio.data.provider.threexpl

import app.lumio.data.BTC
import app.lumio.data.ETH
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.AddressUtxos
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
 * The 3xpl plugin against a local MockWebServer. The balance answer is the one the sandbox gave on 2026-11-15, copied as it came. The other
 * answers are hand-written in the same shape. No test reaches a network.
 */
class ThreeXplProviderTest {
    private val server = MockWebServer()
    private val provider = ThreeXplProvider()
    private val http = Http(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build())
    private val ethAddress = "0x" + "ab".repeat(20)

    /** The sandbox answer for an Ethereum mainnet balance, as it came back on 2026-11-15. */
    private val recordedBalance =
        """{"data":{"balances":{"ethereum-main":{"ethereum":{"balance":"5798969845714110716","events":null}}}},""" +
            """"context":{"code":200,"request_cost":1,"api":{"version":"3.0.0-alpha","notice":":)"},"time":0.049145}}"""

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

    private fun balanceBody(balance: String) =
        """{"data":{"balances":{"ethereum-main":{"ethereum":{"balance":"$balance","events":null}}}},"context":{"code":200}}"""

    @Test
    fun theKeylessEndpointIsTheSandboxAndAKeyUsesTheProductionHost() {
        assertEquals("sandbox-api.3xpl.com", endpointOf(ProviderConfig())!!.host)
        assertEquals("api.3xpl.com", endpointOf(ProviderConfig(apiKey = "KEY-123"))!!.host)
    }

    @Test
    fun theNativeBalanceIsReadFromTheEthereumMainnetModuleAsWei() {
        server.enqueue(MockResponse.Builder().body(recordedBalance).build())

        assertEquals(BigInteger("5798969845714110716"), call(NativeBalance(ETH, ethAddress)))
        val request = server.takeRequest()
        assertEquals("/ethereum/address/$ethAddress", request.url.encodedPath)
        assertEquals("balances", request.url.queryParameter("data"))
        assertEquals("ethereum-main", request.url.queryParameter("from"))
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun aKeyGoesInTheAuthorizationHeaderAndNeverInTheQuery() {
        server.enqueue(MockResponse.Builder().body(recordedBalance).build())

        call(NativeBalance(ETH, ethAddress), cfg(key = "KEY-123"))

        val request = server.takeRequest()
        assertEquals("Bearer KEY-123", request.headers["Authorization"])
        assertNull(request.url.queryParameter("token"))
        assertNull(request.url.queryParameter("key"))
    }

    @Test
    fun aBalanceThatIsNotAPlainIntegerIsRefusedNotRounded() {
        server.enqueue(MockResponse.Builder().body(balanceBody("1.5")).build())
        server.enqueue(MockResponse.Builder().body(balanceBody("1e18")).build())
        server.enqueue(MockResponse.Builder().body(balanceBody("-1")).build())

        repeat(3) { assertFailsWith<ProviderError.Fatal> { call(NativeBalance(ETH, ethAddress)) } }
    }

    @Test
    fun aPaymentRequiredOrQuotaAnswerIsARateLimitWithNoFixedCooldown() {
        server.enqueue(MockResponse.Builder().code(402).build())
        server.enqueue(MockResponse.Builder().code(430).build())

        assertNull(assertFailsWith<ProviderError.RateLimited> { call(NativeBalance(ETH, ethAddress)) }.retryAfterMs)
        assertNull(assertFailsWith<ProviderError.RateLimited> { call(NativeBalance(ETH, ethAddress)) }.retryAfterMs)
    }

    @Test
    fun aRetryAfterHeaderIsTheCooldownInMilliseconds() {
        server.enqueue(MockResponse.Builder().code(429).setHeader("Retry-After", "30").build())

        assertEquals(30_000L, assertFailsWith<ProviderError.RateLimited> { call(NativeBalance(ETH, ethAddress)) }.retryAfterMs)
    }

    @Test
    fun aRejectedKeyIsFatalAndTheMessageHoldsNoKey() {
        server.enqueue(MockResponse.Builder().code(401).build())

        val e = assertFailsWith<ProviderError.Fatal> { call(NativeBalance(ETH, ethAddress), cfg(key = "KEY-123")) }
        assertFalse(e.message.orEmpty().contains("KEY-123"))
    }

    @Test
    fun aNetworkOrOperationThatTheProviderDoesNotServeIsNeverCalled() {
        val sepolia = NetworkId(Networks.evm(11155111), testnet = true)

        assertFailsWith<ProviderError.Unsupported> { call(NativeBalance(BTC, ethAddress)) }
        assertFailsWith<ProviderError.Unsupported> { call(NativeBalance(sepolia, ethAddress)) }
        assertFailsWith<ProviderError.Unsupported> { call(AddressUtxos(ETH, ethAddress)) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theSupportTableHoldsOnlyTheDocumentedNetworksAndOps() {
        val c = cfg()
        assertTrue(provider.supports(ETH, OpKey.on(ETH, "nativeBalance"), c))
        assertFalse(provider.supports(BTC, Ops.BTC_UTXOS, c))
        assertFalse(provider.supports(BTC, OpKey.on(BTC, "nativeBalance"), c))
        assertFalse(provider.supports(ETH, Ops.BTC_UTXOS, c))
        assertFalse(provider.supports(NetworkId(Networks.evm(11155111), testnet = true), OpKey.on(NetworkId(Networks.evm(11155111), testnet = true), "nativeBalance"), c))
    }

    @Test
    fun aPlainHttpBaseToAnotherHostIsNotSupported() {
        assertFalse(provider.supports(ETH, OpKey.on(ETH, "nativeBalance"), ProviderConfig(baseUrl = "http://example.org/")))
    }
}
