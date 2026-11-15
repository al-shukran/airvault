package app.lumio.data.scan

import app.lumio.data.containsBytes
import app.lumio.data.db.LumioDatabase
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.sched.Priority
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The persistent scan queue: its order, its survival across a reopen, and what its database file holds. */
@RunWith(RobolectricTestRunner::class)
class ScanQueueTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private val key = ByteArray(32) { 9 }

    private fun job(walletId: String, priority: Priority) =
        ScanJob(walletId, NetworkId(Family.EVM, "1", testnet = false), "account-0", priority)

    @Test
    fun queuedJobsSurviveAReopenInPriorityOrder() = runBlocking {
        val database = LumioDatabase.open(context, "queue.db")
        val queue = ScanQueue(database.scanJobs(), key)
        val background = queue.enqueue(job("wallet-a", Priority.BACKGROUND))
        val foreground = queue.enqueue(job("wallet-b", Priority.FOREGROUND))
        val finished = queue.enqueue(job("wallet-c", Priority.VISIBLE))
        queue.setPriority(background, Priority.VISIBLE)
        queue.complete(finished)
        database.close()

        val reopened = LumioDatabase.open(context, "queue.db")
        val pending = ScanQueue(reopened.scanJobs(), key).pending()
        reopened.close()

        assertEquals(listOf(foreground, background), pending.map { it.id })
        assertEquals(listOf(Priority.FOREGROUND, Priority.VISIBLE), pending.map { it.job.priority })
        assertEquals("wallet-b", pending.first().job.walletId)
    }

    @Test
    fun theDatabaseFileHoldsNoWalletIdInTheClear() = runBlocking {
        val database = LumioDatabase.open(context, "sealed-queue.db")
        ScanQueue(database.scanJobs(), key).enqueue(job("wallet-marker-552", Priority.VISIBLE))
        database.close()

        val file = context.getDatabasePath("sealed-queue.db").readBytes()

        assertTrue(file.isNotEmpty())
        assertFalse(file.containsBytes("wallet-marker-552".toByteArray()))
    }

    @Test
    fun aQueueOpenedWithAnotherKeyReadsNoJobs() = runBlocking {
        val database = LumioDatabase.open(context, "other-key.db")
        ScanQueue(database.scanJobs(), key).enqueue(job("wallet-x", Priority.VISIBLE))

        val pending = ScanQueue(database.scanJobs(), ByteArray(32) { 1 }).pending()
        database.close()

        assertEquals(emptyList<QueuedJob>(), pending)
    }

    @Test
    fun publishedEventsReachTheSubscriber() = runBlocking {
        val database = LumioDatabase.open(context, "events.db")
        val queue = ScanQueue(database.scanJobs(), key)

        val received = async(start = CoroutineStart.UNDISPATCHED) { queue.events.first() }
        queue.publish(ScanEvent.Progress(jobId = 3L, checked = 2, total = 5))

        assertEquals(ScanEvent.Progress(jobId = 3L, checked = 2, total = 5), received.await())
        database.close()
    }
}
