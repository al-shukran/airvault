package app.lumio.data.electrum

import app.lumio.data.Clock
import app.lumio.data.MonotonicClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads from Electrum servers in turn. [withServer] runs a call on the server that answered last. When that server fails, the call runs on the
 * next one, and so on through the list. A server that fails is skipped for [COOLDOWN_MS], so a dead server costs one attempt, not one per request.
 * Each server keeps one connection. The pool opens it when a call first needs it, and opens it again once it has closed.
 *
 * When every server is cooling or fails, [withServer] throws [ElectrumError.Unavailable], and the caller falls back to another source. An
 * [ElectrumError.Rpc] is a server's answer rather than a failure of the server, so it reaches the caller without another server being asked.
 */
class ElectrumPool(
    servers: List<ElectrumServer> = ElectrumServers.PUBLIC,
    private val clock: Clock = MonotonicClock,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val requestTimeoutMs: Long = REQUEST_TIMEOUT_MS,
    private val open: suspend (ElectrumServer) -> ElectrumConnection = { ElectrumConnection.open(it, connectTimeoutMs, requestTimeoutMs) },
) {
    private class Slot(val server: ElectrumServer) {
        val lock = Mutex()

        /** Guarded by [lock]. */
        var connection: ElectrumConnection? = null

        @Volatile
        var cooldownUntilMs = 0L
    }

    private val slots = servers.map { Slot(it) }

    @Volatile
    private var preferred = 0

    /**
     * Runs [block] on a connection to the first server that answers. A failed attempt runs [block] again on the next server, so [block] must only
     * read.
     * @throws ElectrumError.Unavailable when no server answers.
     */
    suspend fun <T> withServer(block: suspend (ElectrumConnection) -> T): T {
        val failures = mutableListOf<ElectrumError.Transport>()
        val start = preferred
        for (step in slots.indices) {
            val index = (start + step) % slots.size
            val slot = slots[index]
            if (slot.cooldownUntilMs > clock.nowMs()) continue
            try {
                val result = block(connectionOf(slot))
                preferred = index
                return result
            } catch (e: ElectrumError.Transport) {
                failures += e
                disconnect(slot)
            }
        }
        throw ElectrumError.Unavailable(failures)
    }

    private suspend fun connectionOf(slot: Slot): ElectrumConnection = slot.lock.withLock {
        slot.connection?.takeIf { it.isAlive } ?: open(slot.server).also { slot.connection = it }
    }

    private suspend fun disconnect(slot: Slot) {
        slot.cooldownUntilMs = clock.nowMs() + COOLDOWN_MS
        slot.lock.withLock {
            slot.connection?.close()
            slot.connection = null
        }
    }

    companion object {
        /** How long a server that failed is skipped before the pool tries it again. */
        internal const val COOLDOWN_MS = 60_000L

        /** How long a TCP connect and the TLS handshake may take. */
        internal const val CONNECT_TIMEOUT_MS = 8_000L

        /** How long a batch may take, from the send to the last reply. */
        internal const val REQUEST_TIMEOUT_MS = 20_000L
    }
}
