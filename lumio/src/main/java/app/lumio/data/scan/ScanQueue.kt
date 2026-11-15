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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One account to scan: the account [accountRef] of wallet [walletId] on [net], run at [priority]. */
@Serializable
data class ScanJob(val walletId: String, val net: NetworkId, val accountRef: String, val priority: Priority)

/** A queued job and the id the queue gave it. */
data class QueuedJob(val id: Long, val job: ScanJob)

/** What a running job reports, in order. Subscribe to [ScanQueue.events] to follow it. */
sealed interface ScanEvent {
    val jobId: Long

    /** [checked] of [total] addresses of the job are done. */
    data class Progress(override val jobId: Long, val checked: Int, val total: Int) : ScanEvent

    /** The job has finished, and its result is stored. */
    data class Finished(override val jobId: Long) : ScanEvent
}

/**
 * The persistent queue of scan jobs. Each job is sealed under [key] before it is stored, so the database never holds a wallet id or an account in
 * the clear. [events] is the stream the UI will follow. This slice has no worker, so nothing emits events yet.
 */
class ScanQueue(private val dao: ScanJobDao, private val key: ByteArray) {
    private val channel = MutableSharedFlow<ScanEvent>(extraBufferCapacity = EVENT_BUFFER)

    val events: SharedFlow<ScanEvent> = channel.asSharedFlow()

    suspend fun enqueue(job: ScanJob): Long = dao.insert(ScanJobRow(payload = seal(job)))

    /** The queued jobs, most urgent first, then in the order they were queued. A row that [key] does not open is left out. */
    suspend fun pending(): List<QueuedJob> =
        dao.all()
            .mapNotNull { row -> unseal(row)?.let { QueuedJob(row.id, it) } }
            .sortedWith(compareBy<QueuedJob>({ it.job.priority.rank }, { it.id }))

    /** Moves job [id] to [priority]. Does nothing when no such job is queued. */
    suspend fun setPriority(id: Long, priority: Priority) {
        val row = dao.get(id) ?: return
        val job = unseal(row) ?: return
        dao.updatePayload(id, seal(job.copy(priority = priority)))
    }

    /** Removes job [id] once it has finished. */
    suspend fun complete(id: Long) {
        dao.delete(id)
    }

    /** Sends [event] to the subscribers of [events]. */
    internal suspend fun publish(event: ScanEvent) {
        channel.emit(event)
    }

    private fun seal(job: ScanJob): ByteArray =
        Aead.seal(key, AAD, json.encodeToString(ScanJob.serializer(), job).encodeToByteArray())

    private fun unseal(row: ScanJobRow): ScanJob? =
        try {
            json.decodeFromString(ScanJob.serializer(), Aead.open(key, AAD, row.payload).decodeToString())
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
