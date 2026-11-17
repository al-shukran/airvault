package app.lumio.btc

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lumio.data.MonotonicClock
import app.lumio.data.cache.DEFAULT_SHORT_TTL_MS
import app.lumio.data.esplora.BlockstreamProvider
import app.lumio.data.esplora.MempoolProvider
import app.lumio.data.provider.Http
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler
import app.lumio.pair.PairFixtures
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The gap-limit scan through the scheduler, against two local Esplora servers that stand in for mempool.space and blockstream.info. The scan's
 * numbers are checked here; a server that answers 429 is served by the other one, with no wait.
 */
class BtcScanTest {
    private val account = PairFixtures.account(PairFixtures.seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)
    private val ref = BtcAccountRef("wallet-1", account)
    private val first = FakeEsplora()
    private val second = FakeEsplora()
    private val serverA = MockWebServer()
    private val serverB = MockWebServer()

    @Before
    fun start() {
        serverA.dispatcher = first
        serverB.dispatcher = second
        serverA.start()
        serverB.start()
    }

    @After
    fun stop() {
        serverA.shutdown()
        serverB.shutdown()
    }

    /** A scheduler over the two servers, as the mempool and blockstream plugins, both serving testnet4. */
    private fun CoroutineScope.scheduler(): Scheduler = Scheduler(
        providers = listOf(
            MempoolProvider(bases = mapOf("testnet4" to serverA.url("/api").toString())),
            BlockstreamProvider(bases = mapOf("testnet4" to serverB.url("/api").toString())),
        ),
        configs = { emptyMap() },
        http = Http(OkHttpClient()),
        clock = MonotonicClock,
        random = Random(11),
        scope = this,
    )

    /** Both servers answer the same history: the first receive address holds [sats] confirmed, in one unspent output. */
    private fun fund(sats: Long) {
        val address = account.receiveAddress(0)
        val utxo = """[{"txid":"${"a".repeat(64)}","vout":0,"value":$sats,"status":{"confirmed":true,"block_height":850000}}]"""
        for (server in listOf(first, second)) {
            server.stats[address] = addressJson(chain = side(fundedSum = sats, fundedCount = 1, txCount = 1))
            server.utxos[address] = utxo
        }
    }

    @Test
    fun aScanReadsTheBalanceAndTheUnspentOutputsAndStopsAtTheGapLimit() = runBlocking {
        fund(sats = 120_000L)
        val scheduler = this.scheduler()
        val steps = mutableListOf<Int>()

        val snapshot = scanAccount(ref, scheduler, Priority.VISIBLE, nowMs = 5_000L, step = { steps += it })

        assertEquals(120_000L, snapshot.confirmedSats)
        assertEquals(1, snapshot.utxos.size)
        assertEquals(1, snapshot.nextReceiveIndex, "index 0 is used, so the next receive address is index 1")
        // The receive chain asks index 0 and then its 20-address gap: 21 requests. The change chain has no history: 20 requests.
        assertEquals(41, first.statsRequests().size + second.statsRequests().size)
        assertEquals((1..41).toList(), steps.sorted(), "each address asked for is reported once, as the scan goes")
    }

    @Test
    fun aRateLimitedServerIsServedByTheOtherWithoutAWait() = runBlocking {
        fund(sats = 5_000L)
        // Every answer of the first server is a 429 with a 600 s Retry-After. The scan must not wait for it.
        repeat(10) { first.queued.add(MockResponse().setResponseCode(429).setHeader("Retry-After", "600")) }
        val scheduler = this.scheduler()
        val started = System.nanoTime()

        val snapshot = scanAccount(ref, scheduler, Priority.VISIBLE, nowMs = 1L)

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(5_000L, snapshot.confirmedSats, "the second server answers the whole scan")
        // Its lane allows two calls at once, and the first 429 cools it. No call is admitted to it after that, so it sees at most two.
        assertTrue(first.paths.size <= 2, "a cooled server gets no new call: it saw ${first.paths.size}")
        // The second server's own rate (3 calls a second, after a burst of 6) paces the 41 requests: about 12 s. A wait on the 600 s header would fail this.
        assertTrue(elapsedMs < 30_000, "the scan must not wait out the Retry-After: it took $elapsedMs ms")
    }

    /** Both servers answer the same history for [address]: [sats] confirmed, in one used address. */
    private fun setStats(address: String, sats: Long) {
        for (server in listOf(first, second)) {
            server.stats[address] = addressJson(chain = side(fundedSum = sats, fundedCount = 1, txCount = 1))
        }
    }

    @Test
    fun aPaymentBelowAUsedAddressShowsOnceTheEarlierReadIsOlderThanTheTtl() = runBlocking<Unit> {
        setStats(account.receiveAddress(2), 10_000L)
        val scheduler = this.scheduler()
        val memo = ConcurrentHashMap<String, MemoStats>()
        val firstScan = scanAccount(ref, scheduler, Priority.VISIBLE, nowMs = 1_000L, memo = memo)
        assertEquals(10_000L, firstScan.confirmedSats)

        // A payment lands on receive address 1, below the used address 2. Within the TTL the walk still trusts its earlier read of address 1.
        setStats(account.receiveAddress(1), 7_000L)
        val within = scanAccount(ref, scheduler, Priority.VISIBLE, nowMs = 1_000L + DEFAULT_SHORT_TTL_MS - 1, memo = memo)
        assertEquals(10_000L, within.confirmedSats)

        // Once that read is a TTL old, the walk asks again, and the payment shows.
        val after = scanAccount(ref, scheduler, Priority.VISIBLE, nowMs = 1_000L + DEFAULT_SHORT_TTL_MS, memo = memo)
        assertEquals(17_000L, after.confirmedSats)
    }

    @Test
    fun theOpenAccountReadsItsRecentTransactionsAndAPortfolioScanDoesNot() = runBlocking {
        fund(sats = 1_000L)
        val scheduler = this.scheduler()

        scanAccount(ref, scheduler, Priority.VISIBLE, nowMs = 1L, detail = false)
        assertFalse((first.paths + second.paths).any { it.endsWith("/txs") })

        scanAccount(ref, scheduler, Priority.FOREGROUND, nowMs = 2L, detail = true)
        assertTrue((first.paths + second.paths).any { it.endsWith("/txs") })
    }
}
