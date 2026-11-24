package app.lumio.pair

import app.lumio.network.NetworkMode
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The rows of the first sync, in the order their reads run. */
enum class SyncRow { BITCOIN, EVM, SOLANA, ALGORAND, PRICES }

/** Where a row of the first sync is. [Working] turns [Working.slow] once the read has run past [FirstSync.SLOW_AFTER_MS]. */
sealed interface SyncState {
    data object Queued : SyncState
    data class Working(val slow: Boolean) : SyncState
    data object Done : SyncState
    data object Failed : SyncState
}

/**
 * One read of the first sync. It fills [rows], and returns the rows that failed. A read that throws fails all of its rows.
 */
class SyncStep(val rows: List<SyncRow>, val read: suspend () -> Set<SyncRow>)

/**
 * The first sync of a paired wallet: its reads run one after another, and each row shows where its read is. A row that fails does not stop the
 * reads after it, so the sync always settles.
 */
class FirstSync(private val steps: List<SyncStep>) {
    private val states = MutableStateFlow<Map<SyncRow, SyncState>>(steps.flatMap { it.rows }.associateWith { SyncState.Queued })

    /** The state of each row of the sync. A row with no read is absent. */
    val rows: StateFlow<Map<SyncRow, SyncState>> = states.asStateFlow()

    private val started = AtomicBoolean(false)

    /**
     * Runs the reads in order. The reads run once: a call made while they are going, or after they have run, returns at once, so a screen that
     * opens the sync again reattaches to it.
     */
    suspend fun run() {
        if (!started.compareAndSet(false, true)) return
        coroutineScope {
            for (step in steps) {
                update(step.rows) { _, _ -> SyncState.Working(slow = false) }
                val slow = launch {
                    delay(SLOW_AFTER_MS)
                    update(step.rows) { _, state -> if (state is SyncState.Working) SyncState.Working(slow = true) else state }
                }
                val failed = try {
                    step.read()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    step.rows.toSet()
                }
                slow.cancel()
                update(step.rows) { row, _ -> if (row in failed) SyncState.Failed else SyncState.Done }
            }
        }
    }

    private fun update(rows: List<SyncRow>, change: (SyncRow, SyncState) -> SyncState) {
        states.update { current -> current + rows.associateWith { row -> change(row, current.getValue(row)) } }
    }

    companion object {
        /** How long a row may read before it says it is still working. */
        const val SLOW_AFTER_MS = 8_000L

        /** True once every row has either finished or failed. */
        fun isSettled(rows: Map<SyncRow, SyncState>): Boolean = rows.values.all { it == SyncState.Done || it == SyncState.Failed }
    }
}

/** An import of [wallet]: the first sync of the wallet, which runs once however often the import screen opens. */
class FirstSyncImport(val wallet: PairedWallet, val sync: FirstSync)

/**
 * The imports of an open vault, by wallet and network side. Returning to an import reattaches to its sync, so its reads do not run again and its rows
 * show on the first frame. The vault drops this object when it locks.
 */
class FirstSyncImports {
    private val byKey = HashMap<Pair<String, NetworkMode>, FirstSyncImport>()

    @Synchronized
    fun get(walletId: String, mode: NetworkMode): FirstSyncImport? = byKey[walletId to mode]

    @Synchronized
    fun put(walletId: String, mode: NetworkMode, kept: FirstSyncImport) {
        byKey[walletId to mode] = kept
    }
}
