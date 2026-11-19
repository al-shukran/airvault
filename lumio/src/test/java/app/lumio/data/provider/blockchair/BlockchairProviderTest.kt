package app.lumio.data.provider.blockchair

import app.lumio.data.BTC
import app.lumio.data.ETH
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.AddressUtxos
import app.lumio.data.provider.Http
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.Op
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.Utxo
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
 * The Blockchair plugin against a local MockWebServer. The answers are hand-written from the field names in the community changelog (see the
 * KDoc of [BlockchairProvider]). The 430 answer is the one the live host gave on 2026-11-15. No test reaches a network.
 */
class BlockchairProviderTest {
    private val server = MockWebServer()
    private val provider = BlockchairProvider()
    private val http = Http(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build())

    /** A BIP-173 example address, which is public. */
    private val btcAddress = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
    private val ethAddress = "0x" + "ab".repeat(20)

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun cfg(key: String? = null) = ProviderConfig(baseUrl = server.url("/").toString(), apiKey = key)

    private fun <T> call(req: Request<T>, cfg: ProviderConfig = cfg()): T = runBlocking { provider.execute(req, cfg, http) }

    private fun hash(n: Int) = "%064x".format(n)

    private fun output(n: Int, block: Long, value: Long) =
        """{"block_id":$block,"transaction_hash":"${hash(n)}","index":$n,"value":$value}"""

    private fun outputPage(rows: List<String>) =
        """{"data":{"$btcAddress":{"utxo":[${rows.joinToString(",")}]}},"context":{"code":200}}"""

    @Test
    fun theOutputsOfABitcoinAddressMapToSatoshisAndHeights() {
        server.enqueue(MockResponse().setBody(outputPage(listOf(output(1, 812_345, 50_000), output(2, -1, 1_200)))))
        server.enqueue(MockResponse().setBody(outputPage(emptyList())))

        val utxos = call(AddressUtxos(BTC, btcAddress))

        assertEquals(listOf(Utxo(hash(1), 1, 50_000L, 812_345L), Utxo(hash(2), 2, 1_200L, null)), utxos)
        val first = server.takeRequest().requestUrl!!
        assertEquals("/bitcoin/dashboards/address/$btcAddress", first.encodedPath)
        assertEquals("0,1000", first.queryParameter("limit"))
        assertEquals("0,0", first.queryParameter("offset"))
        assertNull(first.queryParameter("key"))
    }

    @Test
    fun aKeyGoesInTheKeyParameterOnEveryCall() {
        server.enqueue(MockResponse().setBody(outputPage(emptyList())))

        call(AddressUtxos(BTC, btcAddress), cfg(key = "KEY-123"))

        assertEquals("KEY-123", server.takeRequest().requestUrl!!.queryParameter("key"))
    }

    @Test
    fun aKeyedPlanHasItsOwnLimits() {
        assertNotEquals(provider.defaultLimits(cfg()), provider.defaultLimits(cfg(key = "KEY-123")))
    }

    @Test
    fun aFullPageIsFollowedUntilAnEmptyPage() {
        server.enqueue(MockResponse().setBody(outputPage((1..1_000).map { output(it, 800_000, it.toLong()) })))
        server.enqueue(MockResponse().setBody(outputPage((1_001..1_003).map { output(it, 800_000, it.toLong()) })))
        server.enqueue(MockResponse().setBody(outputPage(emptyList())))

        val utxos = call(AddressUtxos(BTC, btcAddress))

        assertEquals(1_003, utxos.size)
        assertEquals(1_003L, utxos.last().valueSats)
        server.takeRequest()
        assertEquals("0,1000", server.takeRequest().requestUrl!!.queryParameter("offset"))
        assertEquals("0,1003", server.takeRequest().requestUrl!!.queryParameter("offset"))
    }

    @Test
    fun aSetThatNeverEndsIsRefusedInsteadOfCutShort() {
        repeat(50) { server.enqueue(MockResponse().setBody(outputPage(listOf(output(it + 1, 800_000, 1))))) }

        assertFailsWith<ProviderError.Fatal> { call(AddressUtxos(BTC, btcAddress)) }
        assertEquals(50, server.requestCount)
    }

    @Test
    fun eachPageAfterTheFirstIsChargedBeforeItIsSent() {
        server.enqueue(MockResponse().setBody(outputPage(listOf(output(1, 800_000, 1)))))
        server.enqueue(MockResponse().setBody(outputPage(listOf(output(2, 800_000, 2)))))
        server.enqueue(MockResponse().setBody(outputPage(emptyList())))
        var charges = 0
        val charged = Http(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()) { charges++ }

        val utxos = runBlocking { provider.execute(AddressUtxos(BTC, btcAddress), cfg(), charged) }

        assertEquals(2, utxos.size)
        assertEquals(3, server.requestCount)
        // The first page's request is the one the scheduler admitted. The second and third each take a charge first.
        assertEquals(2, charges)
    }

    @Test
    fun aNativeBalanceIsWeiAsAnExactInteger() {
        server.enqueue(MockResponse().setBody("""{"data":{"$ethAddress":{"address":{"balance":"1500000000000000000"}}},"context":{"code":200}}"""))

        assertEquals(BigInteger("1500000000000000000"), call(NativeBalance(ETH, ethAddress)))
        assertEquals("/ethereum/dashboards/address/$ethAddress", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun aRejectedKeyIsFatalAndTheMessageHoldsNoKey() {
        server.enqueue(MockResponse().setResponseCode(401))

        val e = assertFailsWith<ProviderError.Fatal> { call(NativeBalance(ETH, ethAddress), cfg(key = "KEY-123")) }
        assertFalse(e.message.orEmpty().contains("KEY-123"))
    }

    @Test
    fun theQuotaAnswerOf430IsARateLimitWithNoFixedCooldown() {
        server.enqueue(
            MockResponse().setResponseCode(430).setBody(
                """{"data":null,"context":{"code":430,"error":"Your IP address is temporary blacklisted due to exceeding usage of API resources."}}""",
            ),
        )

        assertNull(assertFailsWith<ProviderError.RateLimited> { call(AddressUtxos(BTC, btcAddress)) }.retryAfterMs)
    }

    @Test
    fun aBanOf429IsCooledForAnHourWhenNoRetryAfterIsSent() {
        server.enqueue(MockResponse().setResponseCode(429))

        assertEquals(3_600_000L, assertFailsWith<ProviderError.RateLimited> { call(AddressUtxos(BTC, btcAddress)) }.retryAfterMs)
    }

    @Test
    fun aRetryAfterHeaderIsTheCooldownInMilliseconds() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))

        assertEquals(7_000L, assertFailsWith<ProviderError.RateLimited> { call(AddressUtxos(BTC, btcAddress)) }.retryAfterMs)
    }

    @Test
    fun aPaymentRequiredAnswerOf402IsARateLimit() {
        server.enqueue(MockResponse().setResponseCode(402))

        assertNull(assertFailsWith<ProviderError.RateLimited> { call(NativeBalance(ETH, ethAddress)) }.retryAfterMs)
    }

    @Test
    fun aServerErrorIsTransient() {
        server.enqueue(MockResponse().setResponseCode(503))

        assertFailsWith<ProviderError.Transient> { call(NativeBalance(ETH, ethAddress)) }
    }

    @Test
    fun anAnswerThatIsNotJsonIsTransientAndOneOfAnotherShapeIsFatal() {
        server.enqueue(MockResponse().setBody("<html>portal</html>"))
        server.enqueue(MockResponse().setBody("""{"data":null,"context":{"code":200}}"""))

        assertFailsWith<ProviderError.Transient> { call(NativeBalance(ETH, ethAddress)) }
        assertFailsWith<ProviderError.Fatal> { call(NativeBalance(ETH, ethAddress)) }
    }

    @Test
    fun aNetworkOrOperationThatTheProviderDoesNotServeIsNeverCalled() {
        val testnet4 = NetworkId(Networks.BTC_TESTNET4, testnet = true)
        val sepolia = NetworkId(Networks.evm(11155111), testnet = true)

        assertFailsWith<ProviderError.Unsupported> { call(AddressUtxos(testnet4, btcAddress)) }
        assertFailsWith<ProviderError.Unsupported> { call(NativeBalance(sepolia, ethAddress)) }
        assertFailsWith<ProviderError.Unsupported> { call(AddressUtxos(ETH, ethAddress)) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theSupportTableHoldsOnlyTheDocumentedNetworksAndOps() {
        val c = cfg()
        assertTrue(provider.supports(BTC, Op.ADDRESS_UTXOS, c))
        assertTrue(provider.supports(ETH, Op.NATIVE_BALANCE, c))
        assertFalse(provider.supports(BTC, Op.ADDRESS_STATS, c))
        assertFalse(provider.supports(ETH, Op.ADDRESS_UTXOS, c))
        assertFalse(provider.supports(NetworkId(Networks.BTC_TESTNET4, testnet = true), Op.ADDRESS_UTXOS, c))
        assertFalse(provider.supports(NetworkId(Networks.evm(11155111), testnet = true), Op.NATIVE_BALANCE, c))
    }

    @Test
    fun aPlainHttpBaseToAnotherHostIsNotSupported() {
        assertFalse(provider.supports(ETH, Op.NATIVE_BALANCE, ProviderConfig(baseUrl = "http://example.org/")))
    }

    @Test
    fun anInvalidAddressIsNotFoundAndIsNeverSent() {
        assertFailsWith<ProviderError.NotFound> { call(NativeBalance(ETH, "../../etc")) }
        assertEquals(0, server.requestCount)
    }
}
