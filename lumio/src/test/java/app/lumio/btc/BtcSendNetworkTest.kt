package app.lumio.btc

import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcCoin
import app.lensly.core.btc.BtcFixtures
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcSigner
import app.lensly.core.btc.ScriptType
import app.lensly.core.wallet.toHex
import app.lumio.data.MonotonicClock
import app.lumio.data.config.ProviderConfig
import app.lumio.data.esplora.BlockstreamProvider
import app.lumio.data.esplora.MempoolProvider
import app.lumio.data.provider.Http
import app.lumio.data.provider.ProviderError
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler
import app.lumio.pair.PairFixtures
import com.sparrowwallet.hummingbird.URDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import fr.acinq.bitcoin.Transaction
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The spend walk and the broadcast over a local Esplora. [FakeEsplora] answers the history, the UTXO and the tip. This test adds the raw
 * transaction of `GET /tx/{txid}/hex` and the `POST /tx` broadcast, and records every posted body. Nothing reaches the network.
 */
class BtcSendNetworkTest {
    private val testnet4 = BtcNetwork.TESTNET4
    private val account = BtcAccount.fromSeed(PairFixtures.seed, testnet4, ScriptType.NATIVE_SEGWIT)
    private val watch = account.watchOnly()
    private val fake = FakeEsplora()
    private val raw = mutableMapOf<String, String>()
    private val posted = CopyOnWriteArrayList<String>()
    /** The answer to each `POST /tx`, given the posted body. A test sets it, so the default refuses. */
    private var answerToPost: (String) -> MockResponse = { MockResponse().setResponseCode(500) }
    private val server = MockWebServer()

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val parts = path.split("/")
                return when {
                    request.method == "POST" && path == "/api/tx" -> {
                        val body = request.body.readUtf8()
                        posted += body
                        answerToPost(body)
                    }
                    parts.size == 5 && parts[2] == "tx" && parts[4] == "hex" ->
                        raw[parts[3]]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                    else -> fake.dispatch(request)
                }
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun CoroutineScope.scheduler(): Scheduler = Scheduler(
        providers = listOf(MempoolProvider(bases = mapOf("testnet4" to server.url("/api").toString()))),
        configs = { emptyMap() },
        http = Http(OkHttpClient()),
        clock = MonotonicClock,
        random = Random(11),
        scope = this,
    )

    /** A funding of 100,000 sat to receive address 0, served as a confirmed output with its raw transaction. */
    private fun fund(): BtcFixtures.Spend {
        val funding = BtcFixtures.funding(account, 0, 0, 100_000L)
        raw[funding.txid.toString()] = Transaction.write(funding).toHex()
        val address = watch.receiveAddress(0)
        fake.stats[address] = addressJson(chain = side(fundedSum = 100_000L, fundedCount = 1, txCount = 1))
        fake.utxos[address] = """[{"txid":"${funding.txid}","vout":0,"value":100000,"status":{"confirmed":true,"block_height":850000}}]"""
        return BtcFixtures.Spend(funding, account, 0, 0)
    }

    /** A plan that spends the funding and its signed transaction, as Lensly answers it. */
    private fun signedSend(): Pair<BtcSendPlan, Transaction> {
        val spend = fund()
        val coin = BtcCoin(spend.funding, 0, 0, 0)
        val recipient = BtcAccount.fromSeed(PairFixtures.seed, testnet4, ScriptType.NATIVE_SEGWIT, accountIndex = 1).receiveAddress(5)
        val plan = BtcSend.plan(watch, BtcSpendable(listOf(coin), nextChangeIndex = 0), recipient, 30_000L, 2.0)
        val tx = BtcSend.signed(plan, URDecoder.decode(BtcSigner(account).sign(plan.psbt).ur))
        return plan to tx
    }

    @Test
    fun theSpendableCoinsAreTheConfirmedOutputsWithTheirKeyAndTheFirstUnusedChangeAddress() = runBlocking<Unit> {
        fund()
        // Change address 0 has been used before, and it holds nothing now, so the next change goes to index 1.
        fake.stats[watch.changeAddress(0)] = addressJson(chain = side(txCount = 1))

        val spendable = BtcSend.spendable(watch, scheduler())

        assertEquals(1, spendable.coins.size)
        assertEquals(100_000L, spendable.coins[0].valueSats)
        assertEquals(0, spendable.coins[0].branch)
        assertEquals(0, spendable.coins[0].index)
        assertEquals(1, spendable.nextChangeIndex)
    }

    @Test
    fun aSignedTransactionIsPostedAsHexAndItsIdIsChecked() = runBlocking<Unit> {
        val (_, tx) = signedSend()
        posted.clear()
        answerToPost = { MockResponse().setBody("${tx.txid}\n") }

        val txid = BtcSend.broadcast(scheduler(), testnet4, tx)

        assertEquals(tx.txid.toString(), txid)
        assertEquals(listOf(Transaction.write(tx).toHex()), posted.toList())
    }

    @Test
    fun aServerThatReportsAnotherIdIsRefused() = runBlocking<Unit> {
        val (_, tx) = signedSend()
        answerToPost = { MockResponse().setBody("${"f".repeat(64)}\n") }
        assertFailsWith<IllegalArgumentException> { BtcSend.broadcast(scheduler(), testnet4, tx) }
    }

    @Test
    fun aServerThatRefusesTheTransactionFailsTheBroadcast() = runBlocking<Unit> {
        val (_, tx) = signedSend()
        answerToPost = { MockResponse().setResponseCode(400).setBody("bad-txns-inputs-missingorspent") }
        val error = assertFailsWith<BtcReadException> { BtcSend.broadcast(scheduler(), testnet4, tx) }
        assertEquals(BtcReadStep.BROADCAST, error.step)
        assertTrue(error.message.orEmpty().contains("sending the transaction"), error.message)
    }

    @Test
    fun aSendAfterAScanAsksTheServerForNoAddressHistory() = runBlocking<Unit> {
        fund()
        val memo = ConcurrentHashMap<String, MemoStats>()
        val scheduler = scheduler()
        scanAccount(BtcAccountRef("wallet-1", watch), scheduler, Priority.VISIBLE, nowMs = 1_000L, memo = memo)
        fake.paths.clear()

        val spendable = BtcSend.spendable(watch, scheduler, memo, nowMs = 1_000L + 5_000L)

        assertEquals(1, spendable.coins.size)
        assertEquals(emptyList(), fake.statsRequests(), "the scan read every address a minute ago, so the send asks for none")
    }

    @Test
    fun aFailedReadNamesTheStepThatFailed() = runBlocking<Unit> {
        fund()
        fake.status = 500
        val error = assertFailsWith<BtcReadException> { BtcSend.spendable(watch, scheduler()) }

        assertEquals(BtcReadStep.ADDRESS_HISTORY, error.step)
        assertTrue(error.message.orEmpty().contains("reading the address history"), error.message)
        assertTrue(error.message.orEmpty().startsWith("The Bitcoin network could not be read"), error.message)
    }

    @Test
    fun aMempoolThatTimesOutIsServedByBlockstream() = runBlocking<Unit> {
        fund()
        // mempool.space never answers in time: its headers come 5 s late, and the client gives up after 500 ms.
        val hung = MockWebServer()
        hung.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setHeadersDelay(5, TimeUnit.SECONDS).setBody("[]")
        }
        hung.start()
        try {
            val scheduler = Scheduler(
                providers = listOf(
                    MempoolProvider(bases = mapOf("testnet4" to hung.url("/api").toString())),
                    BlockstreamProvider(bases = mapOf("testnet4" to server.url("/api").toString())),
                ),
                configs = { mapOf("mempool" to ProviderConfig(priority = 0), "blockstream" to ProviderConfig(priority = 1)) },
                http = Http(OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).build()),
                clock = MonotonicClock,
                random = Random(11),
                scope = this,
            )

            val spendable = BtcSend.spendable(watch, scheduler)

            assertEquals(1, spendable.coins.size, "the coins come from blockstream, which answers")
            assertEquals(100_000L, spendable.coins[0].valueSats)
        } finally {
            hung.shutdown()
        }
    }
}
