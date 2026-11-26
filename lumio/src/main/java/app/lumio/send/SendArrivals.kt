package app.lumio.send

import app.lumio.btc.EsploraTxStatus
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.AlgorandIndexed
import app.lumio.data.provider.AlgorandPendingInfo
import app.lumio.data.provider.EvmReceipt
import app.lumio.data.provider.SolanaStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where a sent transaction stands: on its way, in the recipient's account, or refused for good (a transaction the network dropped counts as refused). */
enum class Landing { PENDING, LANDED, REFUSED }

/**
 * Posts the arrival notice, which shows only the cover's words. [canPost] is false unless the owner already allows notifications, so an arrival never
 * asks for permission.
 */
interface ArrivalNotice {
    fun canPost(): Boolean

    fun post(id: String)
}

/** The notice of a vault that is opened without one: it never posts. */
internal object NoArrivalNotice : ArrivalNotice {
    override fun canPost(): Boolean = false

    override fun post(id: String) = Unit
}

/**
 * One sent transaction that a send page shows: the open vault's [arrivals], the [network] it went out on, the heading that replaces "Is on Its Way" when it
 * lands, or null for a send that announces nothing (a setup), and [read], the chain read that says where the transaction id stands.
 */
class ArrivalWatch(
    val arrivals: SendArrivals,
    val network: NetworkId,
    val arrivedHeadline: String?,
    val read: suspend (id: String) -> Landing,
) {
    /** Where the transaction [id] stands, watched from the first call. A send with a heading also posts the notice when it lands. */
    fun landingOf(id: String): StateFlow<Landing> =
        arrivals.watch(network, id, notify = arrivedHeadline != null) { read(id) }
}

/**
 * The sent transactions that are watched until they land or are refused. Each open vault holds one, polling on the vault's own scope, so a watch keeps
 * polling after the owner leaves the send page and its state is still there when the owner comes back, but a lock ends it: [clear] drops the
 * watches, and the scope's cancellation stops the polls. Each transaction has one watch, so one poll loop however many pages ask for it. A watch
 * reads its chain with backoff, doubling from [FIRST_DELAY_MS] up to [MAX_DELAY_MS], and stops when the chain reports the transaction landed or refused.
 * A read that fails counts as pending and is read again.
 *
 * [foreground] says whether the app is on screen. The notice is posted only when it is not, only if [ArrivalNotice.canPost] holds, and only for a watch
 * that asks for one.
 */
class SendArrivals(
    private val scope: CoroutineScope,
    private val notice: ArrivalNotice,
    private val foreground: () -> Boolean,
) {
    private val watches = HashMap<Pair<NetworkId, String>, MutableStateFlow<Landing>>()

    /**
     * Where the transaction [id] on [network] stands. The first call starts its watch, which reads the chain with [read]; later calls for the same
     * transaction get the same state and start nothing. [notify] says whether the notice posts when it lands.
     */
    @Synchronized
    fun watch(network: NetworkId, id: String, notify: Boolean, read: suspend () -> Landing): StateFlow<Landing> {
        watches[network to id]?.let { return it }
        val landing = MutableStateFlow(Landing.PENDING)
        watches[network to id] = landing
        scope.launch { poll(id, landing, notify, read) }
        return landing
    }

    /** Forgets every watch. The vault calls it when it locks, after its scope has cancelled the polls. */
    @Synchronized
    fun clear() = watches.clear()

    /** Where the watched transaction [id] on [network] stands now, or pending when no watch holds it. */
    @Synchronized
    fun landingNow(network: NetworkId, id: String): Landing = watches[network to id]?.value ?: Landing.PENDING

    private suspend fun poll(id: String, landing: MutableStateFlow<Landing>, notify: Boolean, read: suspend () -> Landing) {
        var wait = FIRST_DELAY_MS
        while (true) {
            val answer = answerOf(read)
            landing.value = answer
            when (answer) {
                Landing.LANDED -> {
                    if (notify && !foreground() && notice.canPost()) notice.post(id)
                    return
                }
                Landing.REFUSED -> return
                Landing.PENDING -> Unit
            }
            delay(wait)
            wait = minOf(wait * 2, MAX_DELAY_MS)
        }
    }

    private suspend fun answerOf(read: suspend () -> Landing): Landing = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Landing.PENDING
    }

    companion object {
        const val FIRST_DELAY_MS = 2_000L
        const val MAX_DELAY_MS = 60_000L
    }
}

/**
 * EVM: a receipt decides once it exists, so a successful one has landed and a reverted one is refused. Without a receipt, [nonceUsed] says whether the
 * account's confirmed nonce has passed the transaction's: then another transaction took the nonce, and this one was dropped. Before that it is pending.
 */
internal fun evmLanding(receipt: EvmReceipt?, nonceUsed: Boolean): Landing = when {
    receipt != null -> if (receipt.success) Landing.LANDED else Landing.REFUSED
    nonceUsed -> Landing.REFUSED
    else -> Landing.PENDING
}

/** Bitcoin: the first confirmation puts the coins in the recipient's wallet. */
internal fun bitcoinLanding(status: EsploraTxStatus): Landing = if (status.confirmed) Landing.LANDED else Landing.PENDING

/**
 * Solana: a status that failed on chain is refused, and a confirmed status has landed. Without a status, [dropped] says whether the network can no
 * longer include the transaction, which refuses it. Before that it is pending.
 */
internal fun solanaLanding(status: SolanaStatus?, dropped: Boolean): Landing = when {
    status == null -> if (dropped) Landing.REFUSED else Landing.PENDING
    status.failed -> Landing.REFUSED
    status.confirmed -> Landing.LANDED
    else -> Landing.PENDING
}

/**
 * Algorand: while the pool holds the transaction, its answer decides: a pool error refuses it, and a confirmed round lands it. Once the pool lets go,
 * [indexed] says whether the indexer holds the transaction, which lands it. Otherwise it was dropped once the indexer has read past the last round the
 * transaction was valid in, since no round after that can include it. Before then it is still pending.
 */
internal fun algorandLanding(pool: AlgorandPendingInfo?, indexed: AlgorandIndexed?, lastValid: ULong): Landing = when {
    pool != null && pool.poolError.isNotEmpty() -> Landing.REFUSED
    pool != null && pool.confirmedRound > 0 -> Landing.LANDED
    pool != null -> Landing.PENDING
    indexed?.confirmedRound != null -> Landing.LANDED
    indexed != null && indexed.indexerRound >= lastValid.toLong() -> Landing.REFUSED
    else -> Landing.PENDING
}
