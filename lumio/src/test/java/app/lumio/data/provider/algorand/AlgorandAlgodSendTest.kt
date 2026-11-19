package app.lumio.data.provider.algorand

import app.lumio.data.MonotonicClock
import app.lumio.data.config.ProviderConfig
import app.lumio.data.provider.AlgorandParams
import app.lumio.data.provider.AlgorandPendingInfo
import app.lumio.data.provider.AlgorandPendingTx
import app.lumio.data.provider.Broadcast
import app.lumio.data.provider.Http
import app.lumio.data.provider.OpKey
import app.lumio.data.provider.Ops
import app.lumio.data.provider.ProviderCatalog
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The send calls of the Algorand provider against a local MockWebServer: suggested parameters, broadcast, and the pending state of a transaction. The
 * 429 tests check the refusal as the provider reports it and the back-off the scheduler takes from it. No test reaches a network.
 */
class AlgorandAlgodSendTest {
    private val server = MockWebServer()
    private val http = Http(OkHttpClient())
    private val cfg = ProviderConfig()
    private val raw = byteArrayOf(0x83.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05)

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun localBase(): String = server.url("/").toString().trimEnd('/')

    private fun provider() = AlgorandAlgod(mapOf("testnet" to localBase()))

    private fun <T> call(req: Request<T>): T = runBlocking { provider().execute(req, cfg, http) }

    private fun params(genesisHash: String = GENESIS_HASH, minFee: String = "1000") = MockResponse().setBody(
        """{"consensus-version":"v41","fee":0,"genesis-hash":"$genesisHash","genesis-id":"testnet-v1.0","last-round":61000000,"min-fee":$minFee}""",
    )

    @Test
    fun theSuggestedParamsComeFromTheParamsEndpoint() {
        server.enqueue(params())

        val suggested = call(AlgorandParams(ALGORAND_TESTNET))

        assertEquals("testnet-v1.0", suggested.genesisId)
        assertContentEquals(java.util.Base64.getDecoder().decode(GENESIS_HASH), suggested.genesisHash)
        assertEquals(61_000_000L, suggested.lastRound)
        assertEquals(0L, suggested.feePerByte)
        assertEquals(1_000L, suggested.minFee)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v2/transactions/params", request.path)
    }

    @Test
    fun aGenesisHashOfTheWrongLengthOrACutOffParamsAnswerIsMalformed() {
        server.enqueue(params(genesisHash = "AAAA"))
        server.enqueue(params(genesisHash = "!!!!"))
        server.enqueue(params(minFee = "-1"))
        server.enqueue(MockResponse().setBody("""{"genesis-id":"testnet-v1.0","genesis-hash":"$GENESIS_HASH","last-round":1}"""))

        repeat(4) { assertFailsWith<ProviderError.Fatal> { call(AlgorandParams(ALGORAND_TESTNET)) } }
    }

    @Test
    fun theBroadcastPostsTheSignedBytesAndReturnsTheTransactionId() {
        server.enqueue(MockResponse().setBody("""{"txId":"$TXID"}"""))

        assertEquals(TXID, call(Broadcast(ALGORAND_TESTNET, raw)))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v2/transactions", request.path)
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/x-binary"))
        assertContentEquals(raw, request.body.readByteArray())
    }

    @Test
    fun aTransactionIdThatIsNotBase32IsMalformed() {
        server.enqueue(MockResponse().setBody("""{"txId":"not-a-transaction-id"}"""))

        assertFailsWith<ProviderError.Fatal> { call(Broadcast(ALGORAND_TESTNET, raw)) }
    }

    @Test
    fun aBroadcastThatTheNodeRefusesIsFatal() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"overspend"}"""))

        assertFailsWith<ProviderError.Fatal> { call(Broadcast(ALGORAND_TESTNET, raw)) }
    }

    @Test
    fun aBroadcastThatIsRateLimitedKeepsTheRetryAfter() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))

        val error = assertFailsWith<ProviderError.RateLimited> { call(Broadcast(ALGORAND_TESTNET, raw)) }

        assertEquals(7_000L, error.retryAfterMs)
    }

    @Test
    fun aBroadcastRateLimitedByTheNodeIsSentAgainAfterItsRetryAfter() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setBody("""{"txId":"$TXID"}"""))
        val scheduler = Scheduler(
            providers = listOf(provider()),
            configs = { ProviderCatalog.withDefaults(emptyMap()) },
            http = http,
            clock = MonotonicClock,
            random = Random(7),
            scope = this,
        )

        val started = System.nanoTime()
        val txid = scheduler.run(Broadcast(ALGORAND_TESTNET, raw), Priority.FOREGROUND)

        assertEquals(TXID, txid)
        assertEquals(2, server.requestCount)
        assertTrue((System.nanoTime() - started) >= 900_000_000L, "the second send waits for the Retry-After")
        server.takeRequest()
        assertContentEquals(raw, server.takeRequest().body.readByteArray())
    }

    @Test
    fun aPendingTransactionHasNoConfirmedRound() {
        server.enqueue(MockResponse().setBody("""{"pool-error":"","txn":{"txn":{"type":"pay"}}}"""))

        assertEquals(AlgorandPendingInfo(0L, ""), call(AlgorandPendingTx(ALGORAND_TESTNET, TXID)))

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v2/transactions/pending/$TXID", request.path)
    }

    @Test
    fun aConfirmedTransactionReportsTheRoundThatConfirmedIt() {
        server.enqueue(MockResponse().setBody("""{"confirmed-round":61000012,"pool-error":""}"""))

        assertEquals(AlgorandPendingInfo(61_000_012L, ""), call(AlgorandPendingTx(ALGORAND_TESTNET, TXID)))
    }

    @Test
    fun aTransactionTheNodeRefusedReportsWhatTheNodeSays() {
        server.enqueue(MockResponse().setBody("""{"pool-error":"transaction fee too small"}"""))

        assertEquals(AlgorandPendingInfo(0L, "transaction fee too small"), call(AlgorandPendingTx(ALGORAND_TESTNET, TXID)))
    }

    @Test
    fun aTransactionThePoolDoesNotHoldIsNotFound() {
        server.enqueue(MockResponse().setResponseCode(404))

        assertFailsWith<ProviderError.NotFound> { call(AlgorandPendingTx(ALGORAND_TESTNET, TXID)) }
    }

    @Test
    fun aPendingRequestForAMalformedTransactionIdIsNotSent() {
        assertFailsWith<IllegalArgumentException> { AlgorandPendingTx(ALGORAND_TESTNET, "../$TXID") }
        assertFailsWith<IllegalArgumentException> { AlgorandPendingTx(ALGORAND_TESTNET, TXID.lowercase()) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theSendOperationsAreServedOnAlgorandOnly() {
        val provider = provider()

        assertTrue(provider.supports(ALGORAND_TESTNET, Ops.ALGO_SUGGESTED_PARAMS, cfg))
        assertTrue(provider.supports(ALGORAND_TESTNET, Ops.ALGO_PENDING_TX, cfg))
        assertTrue(provider.supports(ALGORAND_TESTNET, OpKey.on(ALGORAND_TESTNET, "broadcast"), cfg))
        assertFalse(provider.supports(ALGORAND_TESTNET, Ops.BTC_TX, cfg))
        assertFalse(provider.supports(ALGORAND_MAINNET, OpKey.on(ALGORAND_MAINNET, "broadcast"), cfg), "the mainnet endpoint is not set here")
    }

    private companion object {
        /** The genesis hash of testnet-v1.0, as algod writes it: base64 of 32 bytes. */
        const val GENESIS_HASH = "SGO1GKSzyE7IEPItTxCByw9x8FmnrCDexi9/cOUJOiI="

        /** A well-formed transaction ID for the mock's answers. The mock does not check it against the bytes that were sent. */
        const val TXID = "VOLN5LOJUU2NAHUPUHYMFX5TXEZI4WIHJDUOLLXGLO6MX53SUEUA"
    }
}
