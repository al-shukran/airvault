package app.lumio.btc

import app.airvault.chains.bitcoin.signer.BtcPsbt
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcCoin
import app.lensly.core.btc.BtcFixtures
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.wallet.toHex
import app.lumio.data.Clock
import app.lumio.data.MonotonicClock
import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.esplora.BlockstreamProvider
import app.lumio.data.esplora.MempoolProvider
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.Http
import app.lumio.data.provider.Kind
import app.lumio.data.provider.OpKey
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.Trait
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler
import app.lumio.pair.PairFixtures
import com.sparrowwallet.hummingbird.URDecoder
import fr.acinq.bitcoin.Transaction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Zero-delay test limits. Esplora's production limit of 3 requests a second would pace a scan of 40 addresses to about 11 s of real time. The
 * production limits stay as they are; these only let the tests' scans finish at once.
 */
private val FAST_LIMITS = Limits(rps = 1_000.0, burst = 1_000, maxConcurrent = 8)

/** A provider that is down: every request fails as a transient error, as a server's 500 does. */
private class DownProvider : Provider {
    override val id = "down"
    override val kind = Kind.DATA
    override val traits: Set<Trait> = setOf(Trait.KEYLESS)

    override fun supports(net: NetworkId, op: OpKey, cfg: ProviderConfig): Boolean = true

    override fun defaultLimits(cfg: ProviderConfig): Limits = FAST_LIMITS

    override suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T = throw ProviderError.Transient()
}

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
    private var answerToPost: (String) -> MockResponse = { MockResponse.Builder().code(500).build() }
    private val server = MockWebServer()

    /** The answer to each indexer lookup, given its path. A test sets it, so the default is a server error. */
    private var ordAnswer: (String) -> MockResponse = { MockResponse.Builder().code(500).build() }
    private val ordAsked = CopyOnWriteArrayList<String>()

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target
                val parts = path.split("/")
                return when {
                    request.method == "POST" && path == "/api/tx" -> {
                        val body = request.body?.utf8().orEmpty()
                        posted += body
                        answerToPost(body)
                    }
                    parts.size == 5 && parts[2] == "tx" && parts[4] == "hex" ->
                        raw[parts[3]]?.let { MockResponse.Builder().body(it).build() } ?: MockResponse.Builder().code(404).build()
                    path.startsWith("/ord/") -> {
                        ordAsked += path
                        ordAnswer(path)
                    }
                    else -> fake.dispatch(request)
                }
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun CoroutineScope.scheduler(): Scheduler = Scheduler(
        providers = listOf(MempoolProvider(bases = mapOf("testnet4" to server.url("/api").toString()))),
        configs = { mapOf("mempool" to ProviderConfig(limits = FAST_LIMITS)) },
        http = Http(OkHttpClient()),
        clock = MonotonicClock,
        random = Random(11),
        scope = this,
    )

    /**
     * A scheduler in virtual time. Its clock and its waits are the test's, so a retry, a backoff or a circuit that stays open for a minute
     * runs at once. Only providers that answer in-process may use it: a real socket is not in virtual time.
     */
    private fun TestScope.virtualScheduler(vararg providers: Provider): Scheduler = Scheduler(
        providers = providers.toList(),
        configs = { emptyMap() },
        http = Http(OkHttpClient()),
        clock = Clock { testScheduler.currentTime },
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
        val tx = BtcSend.signed(plan, URDecoder.decode(BtcFixtures.signerOf(account, PairFixtures.seed).sign(plan.psbt).ur))
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
        answerToPost = { MockResponse.Builder().body("${tx.txid}\n").build() }

        val txid = BtcSend.broadcast(scheduler(), testnet4, tx)

        assertEquals(tx.txid.toString(), txid)
        assertEquals(listOf(Transaction.write(tx).toHex()), posted.toList())
    }

    @Test
    fun aServerThatReportsAnotherIdIsRefused() = runBlocking<Unit> {
        val (_, tx) = signedSend()
        answerToPost = { MockResponse.Builder().body("${"f".repeat(64)}\n").build() }
        assertFailsWith<IllegalArgumentException> { BtcSend.broadcast(scheduler(), testnet4, tx) }
    }

    @Test
    fun aServerThatRefusesTheTransactionFailsTheBroadcast() = runBlocking<Unit> {
        val (_, tx) = signedSend()
        answerToPost = { MockResponse.Builder().code(400).body("bad-txns-inputs-missingorspent").build() }
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
    fun aFailedReadNamesTheStepThatFailed() = runTest {
        // Five transient failures in a row open the scheduler's circuit for 60 s, and a call gives up at 30 s. Both waits run in virtual time.
        val error = assertFailsWith<BtcReadException> { BtcSend.spendable(watch, virtualScheduler(DownProvider())) }

        assertEquals(BtcReadStep.ADDRESS_HISTORY, error.step)
        assertTrue(error.message.orEmpty().contains("reading the address history"), error.message)
        assertTrue(error.message.orEmpty().startsWith("The Bitcoin network could not be read"), error.message)
        assertTrue(error.message.orEmpty().contains("the server did not answer in time"), error.message)
    }

    @Test
    fun aMempoolThatTimesOutIsServedByBlockstream() = runBlocking<Unit> {
        fund()
        // mempool.space never answers in time: its headers come 5 s late, and the client gives up after 500 ms.
        val hung = MockWebServer()
        hung.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().headersDelay(5, TimeUnit.SECONDS).body("[]").build()
        }
        hung.start()
        try {
            val scheduler = Scheduler(
                providers = listOf(
                    MempoolProvider(bases = mapOf("testnet4" to hung.url("/api").toString())),
                    BlockstreamProvider(bases = mapOf("testnet4" to server.url("/api").toString())),
                ),
                configs = {
                    mapOf(
                        "mempool" to ProviderConfig(priority = 0, limits = FAST_LIMITS),
                        "blockstream" to ProviderConfig(priority = 1, limits = FAST_LIMITS),
                    )
                },
                http = Http(OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).build()),
                clock = MonotonicClock,
                random = Random(11),
                scope = this,
            )

            val spendable = BtcSend.spendable(watch, scheduler)

            assertEquals(1, spendable.coins.size, "the coins come from blockstream, which answers")
            assertEquals(100_000L, spendable.coins[0].valueSats)
        } finally {
            hung.close()
        }
    }

    private fun ordIndexer(): OrdIndexer = OrdIndexer(Http(OkHttpClient()), server.url("/ord"), budgetMs = 2_000L)

    @Test
    fun aFailingIndexerMarksNothingAndTheSendStillPlansAndSigns() = runBlocking<Unit> {
        val spend = fund()

        val spendable = BtcSend.spendable(watch, scheduler(), indexer = ordIndexer())

        assertEquals(listOf("/ord/output/${spend.funding.txid}:0"), ordAsked.toList())
        assertEquals(listOf(false), spendable.coins.map { it.riskMarked })
        val recipient = BtcAccount.fromSeed(PairFixtures.seed, testnet4, ScriptType.NATIVE_SEGWIT, accountIndex = 1).receiveAddress(5)
        val plan = BtcSend.plan(watch, spendable, recipient, 30_000L, 2.0)
        assertTrue(plan.psbt.inputs.single().unknown.isEmpty(), "an unmarked input carries no mark, so Lensly shows its caution")
        val tx = BtcSend.signed(plan, URDecoder.decode(BtcFixtures.signerOf(account, PairFixtures.seed).sign(plan.psbt).ur))
        assertEquals(plan.tx.txOut, tx.txOut)
    }

    @Test
    fun aMarkedOutputIsMarkedAmongTheCoinsAndInTheRequest() = runBlocking<Unit> {
        val spend = fund()
        val inscribed = "/ord/output/${spend.funding.txid}:0"
        ordAnswer = { path ->
            MockResponse.Builder().body(if (path == inscribed) """{"inscriptions":["x"],"runes":{}}""" else """{"inscriptions":[],"runes":{}}""").build()
        }

        val spendable = BtcSend.spendable(watch, scheduler(), indexer = ordIndexer())

        assertEquals(listOf(true), spendable.coins.map { it.riskMarked })
        val recipient = BtcAccount.fromSeed(PairFixtures.seed, testnet4, ScriptType.NATIVE_SEGWIT, accountIndex = 1).receiveAddress(5)
        val plan = BtcSend.plan(watch, spendable, recipient, 30_000L, 2.0)
        assertEquals(listOf(BtcPsbt.RISK_MARK), plan.psbt.inputs.single().unknown)
    }
}
