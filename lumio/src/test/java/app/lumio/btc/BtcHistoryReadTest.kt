package app.lumio.btc

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lumio.data.Clock
import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.AddressStats
import app.lumio.data.provider.AddressUtxos
import app.lumio.data.provider.Http
import app.lumio.data.provider.Kind
import app.lumio.data.provider.Op
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.Trait
import app.lumio.data.sched.Scheduler
import app.lumio.pair.PairFixtures
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * The history walk of a send, against servers in virtual time. A server takes [latencyMs] to answer each request; with [failAfterLatency] it then
 * fails as an HTTP call does when it reaches its call timeout. Every history answer is empty, so the walk finds no coins. The server counts the
 * requests in flight, and keeps the peak.
 */
private class TimedServer(
    override val id: String,
    private val latencyMs: Long,
    private val failAfterLatency: Boolean = false,
) : Provider {
    override val kind = Kind.DATA
    override val traits: Set<Trait> = setOf(Trait.KEYLESS)
    val requests = AtomicInteger(0)
    val peak = AtomicInteger(0)
    private val inFlight = AtomicInteger(0)

    override fun supports(net: NetworkId, op: Op, cfg: ProviderConfig): Boolean =
        net.family == Family.BTC && (op == Op.ADDRESS_STATS || op == Op.ADDRESS_UTXOS)

    override fun defaultLimits(cfg: ProviderConfig): Limits = Limits(rps = 1_000.0, burst = 1_000, maxConcurrent = 8)

    override suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T {
        requests.incrementAndGet()
        peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
        try {
            delay(latencyMs)
        } finally {
            inFlight.decrementAndGet()
        }
        if (failAfterLatency) throw ProviderError.Transient(IOException("The call timed out."))
        @Suppress("UNCHECKED_CAST")
        return when (req) {
            is AddressStats -> EMPTY_STATS as T
            is AddressUtxos -> emptyList<Any>() as T
            else -> throw ProviderError.Fatal("The test server serves no such request.")
        }
    }

    private companion object {
        val EMPTY_STATS = EsploraAddressStats(
            chain = EsploraSide(fundedCount = 0, fundedSum = 0, spentCount = 0, spentSum = 0, txCount = 0),
            mempool = EsploraSide(fundedCount = 0, fundedSum = 0, spentCount = 0, spentSum = 0, txCount = 0),
        )
    }
}

/** Regression tests for a send's history read timing out while the scheduler could still have answered it. Virtual time: no real waits. */
class BtcHistoryReadTest {
    private val account = PairFixtures.account(PairFixtures.seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)

    private fun TestScope.scheduler(vararg servers: Provider): Scheduler = Scheduler(
        providers = servers.toList(),
        configs = { emptyMap() },
        http = Http(OkHttpClient()),
        clock = Clock { testScheduler.currentTime },
        random = Random(11),
        scope = this,
    )

    @Test
    fun aServerThatAnswersAfterFifteenSecondsIsNotCutShort() = runTest {
        // 15 s is the HTTP call timeout of the app's client. The read budget must not be shorter than the scheduler's own deadline.
        val slow = TimedServer("slow", latencyMs = 15_000L)

        val spendable = BtcSend.spendable(account, scheduler(slow))

        assertEquals(0, spendable.coins.size)
        assertTrue(slow.requests.get() >= 40, "the walk asks every address of both chains: ${slow.requests.get()}")
    }

    @Test
    fun aHungServerIsFailedOverToTheSecondServerInsteadOfFailingTheRead() = runTest {
        // The scheduler may offer the hung server a call first, so a few calls wait out its 15 s. The read still completes on the second server.
        val hung = TimedServer("hung", latencyMs = 15_000L, failAfterLatency = true)
        val healthy = TimedServer("healthy", latencyMs = 0L)

        val spendable = BtcSend.spendable(account, scheduler(hung, healthy))

        assertEquals(0, spendable.coins.size)
        assertTrue(healthy.requests.get() >= 40, "the second server answers the walk: ${healthy.requests.get()}")
    }

    @Test
    fun theTwoChainsOfTheWalkAreReadAtOnceAndNeverMoreThanFourRequestsAtOnce() = runTest {
        // 40 addresses at 1 s each: one after another they take 40 s, read as two chains they take about 20 s.
        val server = TimedServer("steady", latencyMs = 1_000L)

        BtcSend.spendable(account, scheduler(server))

        assertEquals(2, server.peak.get(), "one chain of each branch is read at a time")
        assertTrue(testScheduler.currentTime <= 21_000L, "the walk took ${testScheduler.currentTime} ms, one chain after the other would take 40 s")
    }
}
