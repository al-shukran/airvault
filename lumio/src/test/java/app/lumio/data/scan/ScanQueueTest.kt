package app.lumio.data.scan

import app.lumio.data.containsBytes
import app.lumio.data.db.LumioDatabase
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.sched.Priority
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The persistent scan queue on a real Room file: its order, its survival across a reopen, and what its database file holds. */
@RunWith(RobolectricTestRunner::class)
class ScanQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()

    private val key = ByteArray(32) { 9 }

    private fun job(walletId: String, priority: Priority) =
        ScanJob(walletId, NetworkId(Networks.BTC_MAINNET, testnet = false), "account-0", priority)

    private fun queueOn(database: LumioDatabase, rowsKey: ByteArray = key): ScanQueue = ScanQueue { ScanRows(database.scanJobs(), rowsKey) }

    @Test
    fun queuedJobsSurviveAReopenInPriorityOrder() = runBlocking {
        val file = File(tmp.root, "queue.db")
        val database = LumioDatabase.open(context, file)
        val queue = queueOn(database)
        val background = queue.enqueue(job("wallet-a", Priority.BACKGROUND))
        val foreground = queue.enqueue(job("wallet-b", Priority.FOREGROUND))
        val finished = queue.enqueue(job("wallet-c", Priority.VISIBLE))
        queue.setPriority(background, Priority.VISIBLE)
        queue.complete(finished)
        database.close()

        val reopened = LumioDatabase.open(context, file)
        val pending = queueOn(reopened).pending()
        reopened.close()

        assertEquals(listOf(foreground, background), pending.map { it.id })
        assertEquals(listOf(Priority.FOREGROUND, Priority.VISIBLE), pending.map { it.job.priority })
        assertEquals("wallet-b", pending.first().job.walletId)
    }

    @Test
    fun theDatabaseFileHoldsNoWalletIdInTheClear() = runBlocking {
        val file = File(tmp.root, "sealed-queue.db")
        val database = LumioDatabase.open(context, file)
        queueOn(database).enqueue(job("wallet-marker-552", Priority.VISIBLE))
        database.close()

        val bytes = file.readBytes()

        assertTrue(bytes.isNotEmpty())
        assertFalse(bytes.containsBytes("wallet-marker-552".toByteArray()))
    }

    @Test
    fun aQueueOpenedWithAnotherKeyReadsNoJobs() = runBlocking {
        val database = LumioDatabase.open(context, File(tmp.root, "other-key.db"))
        queueOn(database).enqueue(job("wallet-x", Priority.VISIBLE))

        val pending = queueOn(database, rowsKey = ByteArray(32) { 1 }).pending()
        database.close()

        assertEquals(emptyList<QueuedJob>(), pending)
    }

    @Test
    fun publishedEventsReachTheSubscriber() = runBlocking {
        val database = LumioDatabase.open(context, File(tmp.root, "events.db"))
        val queue = queueOn(database)

        val received = async(start = CoroutineStart.UNDISPATCHED) { queue.events.first() }
        queue.publish(ScanEvent.Progress(jobId = 3L, checked = 2))

        assertEquals(ScanEvent.Progress(jobId = 3L, checked = 2), received.await())
        database.close()
    }
}
