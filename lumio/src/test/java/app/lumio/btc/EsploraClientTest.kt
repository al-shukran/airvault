package app.lumio.btc

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lumio.pair.PairFixtures
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The Esplora client and its parsers, against a local MockWebServer. Nothing here reaches the network. */
class EsploraClientTest {
    private val fake = FakeEsplora()
    private val server = MockWebServer()
    private lateinit var base: String

    private val address = PairFixtures.account(PairFixtures.seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT).receiveAddress(0)

    @Before
    fun start() {
        server.dispatcher = fake
        server.start()
        base = server.url("/api").toString()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun addressStatsReadsBothSidesAndCountsAnyTransactionAsUsed() {
        fake.stats[address] = addressJson(
            chain = side(fundedCount = 2, fundedSum = 9_000, spentCount = 1, spentSum = 4_000, txCount = 2),
            mempool = side(fundedCount = 1, fundedSum = 700, txCount = 1),
        )

        val stats = EsploraClient(base).addressStats(address)

        assertEquals(5_000L, stats.chain.net)
        assertEquals(700L, stats.mempool.net)
        assertTrue(stats.used)
        assertEquals(listOf("/api/address/$address"), fake.paths.toList())
    }

    @Test
    fun anAddressWithNoTransactionIsNotUsed() {
        val stats = EsploraClient(base).addressStats(address)

        assertFalse(stats.used)
        assertEquals(0L, stats.chain.net)
    }

    @Test
    fun aMempoolOnlyAddressIsUsed() {
        fake.stats[address] = addressJson(mempool = side(fundedCount = 1, fundedSum = 50, txCount = 1))

        assertTrue(EsploraClient(base).addressStats(address).used)
    }

    @Test
    fun txsReadInputsOutputsAndTheStatusOfEachTransaction() {
        fake.txs[address] = """
            [
              {"txid":"aa","vin":[
                 {"txid":"bb","vout":0,"prevout":{"scriptpubkey_address":"tb1external","value":1000}},
                 {"txid":"cc","vout":1,"prevout":null}],
               "vout":[{"scriptpubkey_address":"$address","value":600},{"scriptpubkey":"6a","value":0}],
               "status":{"confirmed":true,"block_height":849990,"block_time":1700000000}},
              {"txid":"dd","vin":[],"vout":[],"status":{"confirmed":false}}
            ]
        """.trimIndent()

        val txs = EsploraClient(base).addressTxs(address)

        assertEquals(listOf("aa", "dd"), txs.map { it.txid })
        val confirmed = txs[0]
        assertEquals(849_990L, confirmed.blockHeight)
        assertEquals(1_700_000_000L, confirmed.blockTimeSec)
        assertEquals(EsploraPrevout("tb1external", 1_000), confirmed.inputs[0].prevout)
        assertNull(confirmed.inputs[1].prevout, "a coinbase-style input has no previous output")
        assertEquals(EsploraOutput(address, 600), confirmed.outputs[0])
        assertNull(confirmed.outputs[1].address, "an output without an address reads as null")
        assertNull(txs[1].blockHeight, "an unconfirmed transaction has no height")
        assertEquals("/api/address/$address/txs", fake.paths.single())
    }

    @Test
    fun utxosReadConfirmedAndUnconfirmedOutputs() {
        fake.utxos[address] = """
            [{"txid":"aa","vout":1,"status":{"confirmed":true,"block_height":10},"value":5000},
             {"txid":"bb","vout":0,"status":{"confirmed":false},"value":700}]
        """.trimIndent()

        val utxos = EsploraClient(base).addressUtxos(address)

        assertEquals(EsploraUtxo("aa", 1, 5_000, 10), utxos[0])
        assertEquals(EsploraUtxo("bb", 0, 700, null), utxos[1])
    }

    @Test
    fun theTipHeightIsTheTextAnswer() {
        fake.tip = "850000\n"

        assertEquals(850_000L, EsploraClient(base).tipHeight())
        assertEquals("/api/blocks/tip/height", fake.paths.single())
    }

    @Test
    fun feeEstimatesAreReadByConfirmationTarget() {
        assertEquals(mapOf(1 to 87.5, 6 to 12.25, 144 to 1.0), EsploraClient(base).feeEstimates())
    }

    @Test
    fun anHttpErrorIsRefusedWithItsStatus() {
        fake.status = 404

        val error = assertFailsWith<EsploraException> { EsploraClient(base).tipHeight() }

        assertEquals("The server answered HTTP 404.", error.message)
        assertEquals(1, fake.paths.size, "an error that is not busy is not retried")
    }

    @Test
    fun aRateLimitedAnswerIsWaitedOutAsRetryAfterSaysAndThenRetried() {
        fake.queued += MockResponse().setResponseCode(429).setHeader("Retry-After", "2")
        val waits = mutableListOf<Long>()

        val height = EsploraClient(base) { waits += it }.tipHeight()

        assertEquals(850_000L, height)
        assertEquals(listOf(2_000L), waits, "Retry-After of 2 seconds is waited out once")
        assertEquals(2, fake.paths.size)
    }

    @Test
    fun aServerThatStaysBusyIsGivenUpOnAfterFiveRetriesWithBackoff() {
        fake.status = 429
        val waits = mutableListOf<Long>()

        val error = assertFailsWith<EsploraException> { EsploraClient(base) { waits += it }.tipHeight() }

        assertEquals("Rate limited, retry later.", error.message)
        assertEquals(6, fake.paths.size, "one try and five retries")
        assertEquals(5, waits.size)
        // Each wait is the backoff of 1s, 2s, 4s, 8s and 16s, jittered to between half of it and all of it.
        listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L).zip(waits).forEach { (nominal, wait) ->
            assertTrue(wait in nominal / 2..nominal, "a wait of $wait ms for a backoff of $nominal ms")
        }
    }

    @Test
    fun aRetryAfterLongerThanTheCapIsNotWaitedFor() {
        fake.queued += MockResponse().setResponseCode(503).setHeader("Retry-After", "3600")
        val waits = mutableListOf<Long>()

        val error = assertFailsWith<EsploraException> { EsploraClient(base) { waits += it }.tipHeight() }

        assertEquals("Rate limited, retry later.", error.message)
        assertEquals(1, fake.paths.size, "the server asks for an hour, so the scan gives up at once")
        assertTrue(waits.isEmpty())
    }

    @Test
    fun aDamagedAnswerIsRefusedInsteadOfReadAsPartialData() {
        fake.stats[address] = "{\"chain_stats\":"

        assertFailsWith<EsploraException> { EsploraClient(base).addressStats(address) }
    }

    @Test
    fun aMissingFieldIsRefused() {
        fake.stats[address] = """{"chain_stats":{"funded_txo_count":0},"mempool_stats":{}}"""

        assertFailsWith<EsploraException> { EsploraClient(base).addressStats(address) }
    }

    @Test
    fun aTipHeightThatIsNotANumberIsRefused() {
        fake.tip = "not a height"

        assertFailsWith<EsploraException> { EsploraClient(base).tipHeight() }
    }

    @Test
    fun anAnswerLargerThanTheCapIsRefused() {
        fake.tip = " ".repeat(5 shl 20) + "1"

        assertFailsWith<EsploraException> { EsploraClient(base).tipHeight() }
    }

    @Test
    fun anAddressThatIsNotPlainCharactersIsNeverRequested() {
        assertFailsWith<IllegalArgumentException> { EsploraClient(base).addressStats("../tip/height") }

        assertTrue(fake.paths.isEmpty())
    }
}
