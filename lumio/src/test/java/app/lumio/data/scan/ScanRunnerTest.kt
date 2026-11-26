package app.lumio.data.scan

import app.lumio.data.FakeScanJobDao
import app.lumio.data.db.ScanJobDao
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.ProviderError
import app.lumio.data.sched.Priority
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

/** The scan queue and its runner: the order of events, the network filter, failures, resume after a restart, and a locked vault. */
class ScanRunnerTest {
    private val key = ByteArray(32) { 5 }
    private val mainnet = NetworkId(Networks.BTC_MAINNET, testnet = false)
    private val testnet4 = NetworkId(Networks.BTC_TESTNET4, testnet = true)

    private fun queueOver(dao: ScanJobDao, rowsKey: ByteArray = key): ScanQueue = ScanQueue { ScanRows(dao, rowsKey) }

    private suspend fun ScanQueue.add(net: NetworkId, account: String, priority: Priority): Long =
        enqueue(ScanJob(walletId = "wallet-1", net = net, accountRef = account, priority = priority))

    /** Runs [block], and returns the events the queue published while it ran. */
    private suspend fun TestScope.eventsWhile(queue: ScanQueue, block: suspend () -> Unit): List<ScanEvent> {
        val seen = mutableListOf<ScanEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { queue.events.collect { seen += it } }
        block()
        collector.cancel()
        return seen
    }

    @Test
    fun eachAccountFinishesBeforeTheNextOneStartsInPriorityOrder() = runTest {
        val queue = queueOver(FakeScanJobDao())
        val background = queue.add(mainnet, "account-c", Priority.BACKGROUND)
        val visible = queue.add(mainnet, "account-b", Priority.VISIBLE)
        val foreground = queue.add(mainnet, "account-a", Priority.FOREGROUND)
        val runner = ScanRunner(queue, parallelism = 1) { _, step ->
            step(1)
            delay(10)
            step(2)
        }

        val events = eventsWhile(queue) { runner.pass(mainnet) }

        assertEquals(
            listOf(
                ScanEvent.Progress(foreground, 1),
                ScanEvent.Progress(foreground, 2),
                ScanEvent.Finished(foreground, mainnet, "account-a"),
                ScanEvent.Progress(visible, 1),
                ScanEvent.Progress(visible, 2),
                ScanEvent.Finished(visible, mainnet, "account-b"),
                ScanEvent.Progress(background, 1),
                ScanEvent.Progress(background, 2),
                ScanEvent.Finished(background, mainnet, "account-c"),
            ),
            events,
        )
        assertEquals(emptyList(), queue.pending())
    }

    @Test
    fun aPassRunsOnlyTheJobsOfItsNetwork() = runTest {
        val queue = queueOver(FakeScanJobDao())
        queue.add(testnet4, "account-t", Priority.FOREGROUND)
        queue.add(mainnet, "account-m", Priority.VISIBLE)
        val ran = mutableListOf<String>()

        ScanRunner(queue) { job, _ -> ran += job.job.accountRef }.pass(mainnet)

        assertEquals(listOf("account-m"), ran)
        assertEquals(listOf("account-t"), queue.pending().map { it.job.accountRef }, "the testnet4 job waits for its own network")
    }

    @Test
    fun aFailedScanStaysQueuedAndTheNextPassRunsIt() = runTest {
        val queue = queueOver(FakeScanJobDao())
        val id = queue.add(mainnet, "account-a", Priority.VISIBLE)
        var attempts = 0
        val runner = ScanRunner(queue) { _, _ ->
            attempts++
            if (attempts == 1) throw ProviderError.Transient()
        }

        val first = eventsWhile(queue) { runner.pass(mainnet) }

        assertEquals(listOf<ScanEvent>(ScanEvent.Failed(id, mainnet, "account-a", "Could not reach the servers.")), first)
        assertEquals(listOf(id), queue.pending().map { it.id })

        val second = eventsWhile(queue) { runner.pass(mainnet) }

        assertEquals(listOf<ScanEvent>(ScanEvent.Finished(id, mainnet, "account-a")), second)
        assertEquals(emptyList(), queue.pending())
    }

    @Test
    fun jobsQueuedByAnEarlierSessionRunOnTheNextUnlock() = runTest {
        val dao = FakeScanJobDao()
        queueOver(dao).add(mainnet, "account-a", Priority.BACKGROUND)
        val afterRestart = queueOver(dao)
        val ran = mutableListOf<String>()

        ScanRunner(afterRestart) { job, _ -> ran += job.job.accountRef }.pass(mainnet)

        assertEquals(listOf("account-a"), ran)
        assertEquals(emptyList(), afterRestart.pending())
    }

    @Test
    fun aQueueOverAnotherKeyReadsNothingAndItsPurgeRemovesTheRows() = runTest {
        val dao = FakeScanJobDao()
        queueOver(dao).add(mainnet, "account-a", Priority.VISIBLE)
        val otherKey = queueOver(dao, rowsKey = ByteArray(32) { 1 })

        assertEquals(emptyList(), otherKey.pending())
        otherKey.purgeUnreadable()

        assertEquals(0, dao.all().size, "a row that no session can open is deleted when the vault opens")
    }

    @Test
    fun aLockedVaultHoldsNoJobsToRunAndQueuesNothing() = runTest {
        val locked = ScanQueue { null }
        val ran = mutableListOf<String>()

        assertEquals(emptyList(), locked.pending())
        assertFailsWith<IllegalStateException> { locked.add(mainnet, "account-a", Priority.VISIBLE) }
        ScanRunner(locked) { job, _ -> ran += job.job.accountRef }.pass(mainnet)

        assertEquals(emptyList(), ran)
    }

    @Test
    fun aJobIsNeverRunByTwoPassesAtOnce() = runTest {
        val queue = queueOver(FakeScanJobDao())
        queue.add(mainnet, "account-a", Priority.VISIBLE)
        var runs = 0
        val runner = ScanRunner(queue) { _, _ ->
            runs++
            delay(100)
        }

        val first = async { runner.pass(mainnet) }
        val second = async { runner.pass(mainnet) }
        first.await()
        second.await()

        assertEquals(1, runs)
        assertEquals(emptyList(), queue.pending())
    }

    @Test
    fun aPassThatOutlivesTheSessionNeitherDeletesItsJobNorReportsIntoTheNextOne() = runTest {
        val dao = FakeScanJobDao()
        var rowsKey = key
        val queue = ScanQueue { ScanRows(dao, rowsKey) }
        val done = queue.add(mainnet, "account-a", Priority.VISIBLE)
        val failing = queue.add(mainnet, "account-b", Priority.BACKGROUND)
        // The owner locks and opens the decoy, whose key opens none of the real rows, while the pass still runs.
        val runner = ScanRunner(queue, parallelism = 1) { job, _ ->
            rowsKey = ByteArray(32) { 1 }
            if (job.id == failing) throw ProviderError.Transient()
        }

        val events = eventsWhile(queue) { runner.pass(mainnet) }

        assertEquals(emptyList(), events, "the decoy hears nothing of the real scans")
        rowsKey = key
        assertEquals(listOf(done, failing), queue.pending().map { it.id }, "both real jobs are still queued")
    }

    @Test
    fun aFailureIsNamedInWordsTheOwnerCanActOnWithoutTheServerDetail() {
        assertEquals("The servers are rate limiting Lumio. Try again later.", ScanRunner.messageOf(ProviderError.RateLimited(null)))
        assertEquals("A server refused the scan.", ScanRunner.messageOf(ProviderError.Fatal("A server refused the scan.")))
    }

    @Test
    fun aCancelledPassReleasesEveryJobItClaimed() = runTest {
        val queue = queueOver(FakeScanJobDao())
        queue.add(mainnet, "account-a", Priority.FOREGROUND)
        queue.add(mainnet, "account-b", Priority.VISIBLE)
        val ran = mutableListOf<String>()
        var hang = true
        val started = CompletableDeferred<Unit>()
        // One worker, so the second job is still waiting in the pass when the pass is cancelled.
        val runner = ScanRunner(queue, parallelism = 1) { job, _ ->
            if (hang) {
                started.complete(Unit)
                awaitCancellation()
            }
            ran += job.job.accountRef
        }
        val pass = launch(UnconfinedTestDispatcher(testScheduler)) { runner.pass(mainnet) }
        started.await()

        pass.cancel()
        pass.join()
        hang = false

        assertEquals(2, runner.pass(mainnet), "the next pass claims both jobs again")
        assertEquals(listOf("account-a", "account-b"), ran)
    }

    @Test
    fun aFailedJobNeverRunsInTwoPassesAtOnce() = runTest {
        val queue = queueOver(FakeScanJobDao())
        queue.add(mainnet, "account-a", Priority.FOREGROUND)
        queue.add(mainnet, "account-b", Priority.VISIBLE)
        val aFailed = CompletableDeferred<Unit>()
        val bStarted = CompletableDeferred<Unit>()
        val holdA2 = CompletableDeferred<Unit>()
        val holdB = CompletableDeferred<Unit>()
        val current = AtomicInteger()
        val maxA = AtomicInteger()
        var attemptsA = 0
        val runner = ScanRunner(queue, parallelism = 2) { job, _ ->
            if (job.job.accountRef == "account-b") {
                bStarted.complete(Unit)
                holdB.await()
            } else {
                maxA.accumulateAndGet(current.incrementAndGet(), ::maxOf)
                try {
                    attemptsA++
                    when (attemptsA) {
                        1 -> {
                            aFailed.complete(Unit)
                            throw ProviderError.Transient()
                        }
                        2 -> holdA2.await()
                    }
                } finally {
                    current.decrementAndGet()
                }
            }
        }

        val passA = async { runner.pass(mainnet) }
        aFailed.await()
        bStarted.await()
        repeat(3) { yield() }
        // Pass A is still running account b, so a failed account a must not be claimed by another pass yet.
        val passB = async { runner.pass(mainnet) }
        repeat(3) { yield() }
        holdB.complete(Unit)
        passA.await()
        val passC = async { runner.pass(mainnet) }
        repeat(3) { yield() }

        assertEquals(1, maxA.get(), "account a never runs twice at once")
        holdA2.complete(Unit)
        awaitAll(passB, passC)
    }
}
