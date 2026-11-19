package app.lumio.data.scan

import app.lensly.core.vault.Aead
import app.lumio.data.db.ScanJobDao
import app.lumio.data.db.ScanJobRow
import app.lumio.data.net.NetworkId
import app.lumio.data.sched.Priority
import java.security.GeneralSecurityException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** One account to scan: the account [accountRef] of wallet [walletId] on [net], run at [priority]. */
@Serializable
data class ScanJob(val walletId: String, val net: NetworkId, val accountRef: String, val priority: Priority)

/** A queued job and the id the queue gave it. */
data class QueuedJob(val id: Long, val job: ScanJob)

/** What a running job reports. [Progress] comes first, then [Finished] or [Failed]. Subscribe to [ScanQueue.events] to follow it. */
sealed interface ScanEvent {
    val jobId: Long

    /** [checked] addresses of the account have been asked for so far. */
    data class Progress(override val jobId: Long, val checked: Int) : ScanEvent

    /** The account's scan has finished, and its result is stored. The job has left the queue. */
    data class Finished(override val jobId: Long, val accountKey: String) : ScanEvent

    /** The scan failed with [message]. The job stays queued, and the next pass runs it again. */
    data class Failed(override val jobId: Long, val accountKey: String, val message: String) : ScanEvent
}

/** The rows of the open vault's scan queue: the table, and the key that seals each job. */
class ScanRows(val dao: ScanJobDao, val key: ByteArray)

/**
 * The persistent queue of scan jobs, one job per account. Each job is sealed under the vault's key before it is stored, so the database never holds
 * a wallet id or an account in the clear. The queue is application-scoped: [rows] returns the rows of the open vault, and null while the vault is
 * locked. Then the queue reads and writes nothing. [events] is the stream the screens follow.
 */
class ScanQueue(private val rows: () -> ScanRows?) {
    private val channel = MutableSharedFlow<ScanEvent>(extraBufferCapacity = EVENT_BUFFER)

    val events: SharedFlow<ScanEvent> = channel.asSharedFlow()

    /** Queues [job] and returns its id. @throws IllegalStateException while the vault is locked. */
    suspend fun enqueue(job: ScanJob): Long {
        val open = checkNotNull(rows()) { "The vault is locked." }
        return open.dao.insert(ScanJobRow(payload = seal(open, job)))
    }

    /** The queued jobs, most urgent first, then in the order they were queued. A row that the key does not open is left out. */
    suspend fun pending(): List<QueuedJob> {
        val open = rows() ?: return emptyList()
        return open.dao.all()
            .mapNotNull { row -> unseal(open, row)?.let { QueuedJob(row.id, it) } }
            .sortedWith(compareBy<QueuedJob>({ it.job.priority.rank }, { it.id }))
    }

    /** Moves job [id] to [priority]. Does nothing when no such job is queued. */
    suspend fun setPriority(id: Long, priority: Priority) {
        val open = rows() ?: return
        val row = open.dao.get(id) ?: return
        val job = unseal(open, row) ?: return
        open.dao.updatePayload(id, seal(open, job.copy(priority = priority)))
    }

    /** Removes job [id] once it has finished. */
    suspend fun complete(id: Long) {
        rows()?.dao?.delete(id)
    }

    /** Deletes the rows that the key does not open, as an older key left them. Run once when the vault opens. */
    suspend fun purgeUnreadable() {
        val open = rows() ?: return
        for (row in open.dao.all()) {
            if (unseal(open, row) == null) open.dao.delete(row.id)
        }
    }

    /** Sends [event] to the subscribers of [events]. */
    internal suspend fun publish(event: ScanEvent) {
        channel.emit(event)
    }

    private fun seal(open: ScanRows, job: ScanJob): ByteArray =
        Aead.seal(open.key, AAD, json.encodeToString(ScanJob.serializer(), job).encodeToByteArray())

    private fun unseal(open: ScanRows, row: ScanJobRow): ScanJob? =
        try {
            json.decodeFromString(ScanJob.serializer(), Aead.open(open.key, AAD, row.payload).decodeToString())
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: SerializationException) {
            null
        }

    private companion object {
        val AAD = "lumio-scan-job-v1".toByteArray(Charsets.UTF_8)
        val json = Json { ignoreUnknownKeys = true }
        const val EVENT_BUFFER = 64
    }
}
