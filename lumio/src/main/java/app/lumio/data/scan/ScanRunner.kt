package app.lumio.data.scan

import app.lumio.data.net.NetworkId
import app.lumio.data.provider.ProviderError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs the queued scan jobs of one network. Up to [parallelism] accounts scan at once, the most urgent job first, so the account on screen comes
 * before the rest. Each account's result is published as soon as it finishes, as [ScanEvent.Finished]. A job that fails stays queued and is published
 * as [ScanEvent.Failed]; the next pass runs it again. A job that a cancelled pass was running also stays queued. No job runs in two passes at once.
 *
 * A job that the open vault's key does not open publishes nothing: a pass that outlives a lock must not report into the next session, a decoy.
 *
 * [scanAccount] does the scan of one job. It reports progress through the callback it is given, and throws when the scan fails.
 */
class ScanRunner(
    private val queue: ScanQueue,
    private val parallelism: Int = 2,
    private val scanAccount: suspend (QueuedJob, suspend (Int) -> Unit) -> Unit,
) {
    private val lock = Mutex()
    private val running = HashSet<Long>()

    /** Runs every pending job of [net] that no other pass is running, and returns when each has finished or failed. Returns how many it ran. */
    suspend fun pass(net: NetworkId): Int {
        val claimed = lock.withLock {
            queue.pending().filter { it.job.net == net && it.id !in running }.onEach { running += it.id }
        }
        if (claimed.isEmpty()) return 0
        val work = Channel<QueuedJob>(Channel.UNLIMITED)
        claimed.forEach { work.trySend(it) }
        work.close()
        coroutineScope {
            repeat(minOf(parallelism, claimed.size)) {
                launch {
                    for (job in work) runOne(job)
                }
            }
        }
        return claimed.size
    }

    private suspend fun runOne(job: QueuedJob) {
        val account = job.job.accountRef
        try {
            scanAccount(job) { checked -> queue.publish(ScanEvent.Progress(job.id, checked)) }
            if (queue.complete(job.id)) queue.publish(ScanEvent.Finished(job.id, job.job.net, account))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (queue.holds(job.id)) queue.publish(ScanEvent.Failed(job.id, job.job.net, account, messageOf(e)))
        } finally {
            withContext(NonCancellable) { lock.withLock { running -= job.id } }
        }
    }

    internal companion object {
        /** The message a failed scan shows: what the owner can do about it, without the server's detail. */
        fun messageOf(e: Throwable): String = when (e) {
            is ProviderError.RateLimited -> "The servers are rate limiting Lumio. Try again later."
            is ProviderError.Transient -> "Could not reach the servers."
            is ProviderError.Fatal -> e.message ?: "A server refused the scan."
            is ProviderError.NotFound -> "A server has no answer for this account."
            is ProviderError.Unsupported -> "No server serves this network."
            else -> e.message ?: "The scan failed."
        }
    }
}
