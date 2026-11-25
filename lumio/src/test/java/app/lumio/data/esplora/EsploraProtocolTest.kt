package app.lumio.data.esplora

import app.lumio.btc.addressJson
import app.lumio.btc.side
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.AddressStats
import app.lumio.data.provider.AddressTxs
import app.lumio.data.provider.Block
import app.lumio.data.provider.Broadcast
import app.lumio.data.provider.FeeEstimates
import app.lumio.data.provider.Http
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.Ops
import app.lumio.data.provider.retryAfterMs
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.TipHeight
import app.lumio.data.provider.Tx
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The Esplora protocol over a local MockWebServer: each endpoint, each status, and the Retry-After wait. Nothing reaches the network. */
class EsploraProtocolTest {
    private val server = MockWebServer()
    private val http = Http(OkHttpClient())
    private val testnet4 = NetworkId(Networks.BTC_TESTNET4, testnet = true)
    private val address = "tb1qexampleaddressforatestonly0123456789"
    private val txid = "c".repeat(64)
    private val blockHash = "d".repeat(64)

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun base(): String = server.url("/api").toString()

    private fun reply(code: Int, body: String = "", vararg headers: Pair<String, String>) {
        val response = MockResponse.Builder().code(code).body(body)
        headers.forEach { (name, value) -> response.addHeader(name, value) }
        server.enqueue(response.build())
    }

    @Test
    fun anAddressAnswerIsParsedFromItsPath(): Unit = runBlocking {
        reply(200, addressJson(chain = side(fundedSum = 150_000, fundedCount = 2, txCount = 2), mempool = side(txCount = 0)))

        val stats = EsploraProtocol.execute(AddressStats(testnet4, address), base(), http)

        assertEquals(150_000L, stats.balance)
        assertTrue(stats.used)
        assertEquals("/api/address/$address", server.takeRequest().target)
    }

    @Test
    fun aTooManyRequestsAnswerCarriesItsRetryAfterInMilliseconds(): Unit = runBlocking {
        reply(429, "", "Retry-After" to "60")

        val error = assertFailsWith<ProviderError.RateLimited> {
            EsploraProtocol.execute(TipHeight(testnet4), base(), http)
        }

        assertEquals(60_000L, error.retryAfterMs)
    }

    @Test
    fun aTooManyRequestsWithoutRetryAfterLeavesTheWaitToTheScheduler(): Unit = runBlocking {
        reply(429)

        val error = assertFailsWith<ProviderError.RateLimited> { EsploraProtocol.execute(TipHeight(testnet4), base(), http) }

        assertNull(error.retryAfterMs)
    }

    @Test
    fun aRetryAfterDateIsTheTimeUntilThatDate(): Unit = runBlocking {
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(120))
        reply(429, "", "Retry-After" to date)

        val error = assertFailsWith<ProviderError.RateLimited> { EsploraProtocol.execute(TipHeight(testnet4), base(), http) }

        val waited = error.retryAfterMs ?: error("a date should give a wait")
        assertTrue(waited in 100_000L..120_000L, "waited $waited ms")
    }

    @Test
    fun aRetryAfterHeaderIsReadAsSecondsOrNothing() {
        assertEquals(12_000L, retryAfterMs("12", nowMs = 0L))
        assertNull(retryAfterMs("", nowMs = 0L))
        assertNull(retryAfterMs("soon", nowMs = 0L))
        assertNull(retryAfterMs("-1", nowMs = 0L))
        assertNull(retryAfterMs(null, nowMs = 0L))
    }

    @Test
    fun aRetryAfterLongerThanTheOneCapIsCutToIt() {
        assertEquals(3_600_000L, retryAfterMs("7200", nowMs = 0L))
        assertEquals(3_600_000L, retryAfterMs("999999999", nowMs = 0L))
    }

    @Test
    fun aServerErrorIsTransient(): Unit = runBlocking {
        reply(503)

        assertFailsWith<ProviderError.Transient> { EsploraProtocol.execute(TipHeight(testnet4), base(), http) }
    }

    @Test
    fun aMissingTransactionIsNotFoundButAMissingEndpointIsFatal(): Unit = runBlocking {
        reply(404)
        assertFailsWith<ProviderError.NotFound> { EsploraProtocol.execute(Tx(testnet4, txid), base(), http) }

        reply(404)
        assertFailsWith<ProviderError.Fatal> { EsploraProtocol.execute(AddressStats(testnet4, address), base(), http) }
    }

    @Test
    fun aRefusedRequestIsFatalForThisProviderOnly(): Unit = runBlocking {
        reply(403)

        assertFailsWith<ProviderError.Fatal> { EsploraProtocol.execute(TipHeight(testnet4), base(), http) }
    }

    @Test
    fun aRedirectIsNotFollowedSoARequestNeverMovesToAnotherPath(): Unit = runBlocking {
        reply(302, "", "Location" to server.url("/elsewhere").toString())
        reply(200, "850001\n")

        assertFailsWith<ProviderError.Fatal> { EsploraProtocol.execute(TipHeight(testnet4), base(), http) }

        assertEquals(1, server.requestCount)
        assertEquals("/api/blocks/tip/height", server.takeRequest().target)
    }

    @Test
    fun aMalformedAnswerIsFatalNotTransient(): Unit = runBlocking {
        reply(200, "<html>not json</html>")

        assertFailsWith<ProviderError.Fatal> { EsploraProtocol.execute(AddressStats(testnet4, address), base(), http) }
    }

    @Test
    fun aBroadcastAnswerThatIsNotATransactionIdIsUnreadable(): Unit = runBlocking {
        reply(200, "<html>not a txid</html>")

        assertFailsWith<ProviderError.Unreadable> { EsploraProtocol.execute(Broadcast(testnet4, byteArrayOf(1)), base(), http) }
    }

    @Test
    fun anAnswerOverTheSizeCapIsTransientSoItIsNeverCutShort(): Unit = runBlocking {
        reply(200, "x".repeat((4 shl 20) + 1))

        assertFailsWith<ProviderError.Transient> { EsploraProtocol.execute(AddressTxs(testnet4, address), base(), http) }
    }

    @Test
    fun blocksAndTransactionStatusesAreParsedAndCountConfirmations(): Unit = runBlocking {
        reply(200, """{"id":"$blockHash","height":850000,"timestamp":1700000000}""")
        val block = EsploraProtocol.execute(Block(testnet4, blockHash), base(), http)
        assertEquals(850_000L, block.height)
        assertEquals("/api/block/$blockHash", server.takeRequest().target)

        reply(200, """{"txid":"$txid","status":{"confirmed":true,"block_height":850000,"block_hash":"$blockHash","block_time":1700000000}}""")
        val status = EsploraProtocol.execute(Tx(testnet4, txid), base(), http)
        assertEquals(6L, status.confirmationsAt(tipHeight = 850_005L))
        assertEquals("/api/tx/$txid", server.takeRequest().target)

        reply(200, """{"txid":"$txid","status":{"confirmed":false}}""")
        val pending = EsploraProtocol.execute(Tx(testnet4, txid), base(), http)
        assertEquals(0L, pending.confirmationsAt(tipHeight = 850_005L))
    }

    @Test
    fun feeEstimatesAndTheTipAreParsed(): Unit = runBlocking {
        reply(200, """{"1":87.5,"6":12.25}""")
        assertEquals(mapOf(1 to 87.5, 6 to 12.25), EsploraProtocol.execute(FeeEstimates(testnet4), base(), http))

        reply(200, "850001\n")
        assertEquals(850_001L, EsploraProtocol.execute(TipHeight(testnet4), base(), http))
    }

    @Test
    fun identifiersThatCouldReachAnotherPathAreRefusedBeforeAnyCall() {
        assertFailsWith<IllegalArgumentException> { AddressStats(testnet4, "../tip/height") }
        assertFailsWith<IllegalArgumentException> { Tx(testnet4, "not-a-txid") }
        assertFailsWith<IllegalArgumentException> { Block(testnet4, "e".repeat(63)) }
    }

    @Test
    fun anOperationTheEsploraServesNotIsUnsupported(): Unit = runBlocking {
        assertFailsWith<ProviderError.Unsupported> {
            EsploraProtocol.execute(NativeBalance(testnet4, address), base(), http)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theMempoolProviderServesItsNetworkUnderItsOwnBaseAndNeverSendsAKey(): Unit = runBlocking {
        val mempool = MempoolProvider(bases = mapOf("testnet4" to base()))
        reply(200, "850002\n")

        val tip = mempool.execute(TipHeight(testnet4), ProviderConfig(apiKey = "secret-key"), http)

        assertEquals(850_002L, tip)
        val request = server.takeRequest()
        assertEquals("/api/blocks/tip/height", request.target)
        assertTrue(!request.headers.toString().contains("secret-key"), "the key must never be sent")
    }

    @Test
    fun aPublicProviderServesBitcoinOnlyAndAnOwnServerOnlyItsChainWithAUrl() {
        val evm = NetworkId(Networks.evm(1), testnet = false)
        val mainnet = NetworkId(Networks.BTC_MAINNET, testnet = false)

        assertTrue(MempoolProvider().supports(testnet4, Ops.BTC_ADDRESS_STATS, ProviderConfig()))
        assertTrue(BlockstreamProvider().supports(testnet4, Ops.BTC_TX, ProviderConfig()))
        assertTrue(BlockstreamProvider().supports(mainnet, Ops.BTC_BLOCK, ProviderConfig()))
        assertTrue(!MempoolProvider().supports(evm, Ops.BTC_TIP_HEIGHT, ProviderConfig()))

        val own = CustomEsploraProvider("testnet4")
        assertTrue(!own.supports(testnet4, Ops.BTC_ADDRESS_STATS, ProviderConfig()), "no URL, no server")
        assertTrue(own.supports(testnet4, Ops.BTC_ADDRESS_STATS, ProviderConfig(baseUrl = "https://node.example.org/api")))
        assertTrue(!own.supports(mainnet, Ops.BTC_ADDRESS_STATS, ProviderConfig(baseUrl = "https://node.example.org/api")))
    }
}
