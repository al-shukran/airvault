package app.lumio.send

import app.lumio.btc.EsploraTxStatus
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.AlgorandPendingInfo
import app.lumio.data.provider.EvmReceipt
import app.lumio.data.provider.SolanaStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the network says about a sent transaction: still on its way, in the recipient's account, or refused for good. */
enum class Landing { PENDING, LANDED, REFUSED }

/** Posts the arrival notice. [canPost] is false unless the owner already allows notifications, so an arrival never asks for permission. */
interface ArrivalNotice {
    fun canPost(): Boolean

    fun post(id: String, title: String, text: String)
}

/**
 * One sent transaction that a send page shows: the app's [arrivals], the [network] it went out on, the heading that replaces "Is on Its Way" when it
 * lands, and [read], the chain read that says where the transaction id stands.
 */
class ArrivalWatch(
    val arrivals: SendArrivals,
    val network: NetworkId,
    val arrivedHeadline: String,
    val read: suspend (id: String) -> Landing,
) {
    /** Whether the transaction [id] has arrived, watched from the first call. [text] is the notice's second line. */
    fun arrivedFor(id: String, text: String): StateFlow<Boolean> = arrivals.watch(network, id, arrivedHeadline, text) { read(id) }
}

/**
 * The sent transactions that are watched until they land. The app holds one, so a watch keeps polling after the owner leaves the send page, and its
 * state is still there when the owner comes back. A watch reads its chain with backoff, doubling from [FIRST_DELAY_MS] up to [MAX_DELAY_MS], and stops
 * when the chain reports the transaction landed or refused. A read that fails counts as pending and is read again.
 *
 * [foreground] says whether the app is on screen. The notice is posted only when it is not, and only if [ArrivalNotice.canPost] holds.
 */
class SendArrivals(
    private val scope: CoroutineScope,
    private val notice: ArrivalNotice,
    private val foreground: () -> Boolean,
) {
    private val watches = HashMap<Pair<NetworkId, String>, MutableStateFlow<Boolean>>()

    /**
     * Whether the transaction [id] on [network] has arrived. The first call starts its watch, which reads the chain with [read]; later calls for the
     * same transaction get the same state. [title] and [text] are the notice's words.
     */
    @Synchronized
    fun watch(network: NetworkId, id: String, title: String, text: String, read: suspend () -> Landing): StateFlow<Boolean> {
        watches[network to id]?.let { return it }
        val arrived = MutableStateFlow(false)
        watches[network to id] = arrived
        scope.launch { poll(id, arrived, title, text, read) }
        return arrived
    }

    private suspend fun poll(id: String, arrived: MutableStateFlow<Boolean>, title: String, text: String, read: suspend () -> Landing) {
        var wait = FIRST_DELAY_MS
        while (true) {
            when (answerOf(read)) {
                Landing.LANDED -> {
                    arrived.value = true
                    if (!foreground() && notice.canPost()) notice.post(id, title, text)
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

/** EVM: no receipt yet is pending, a receipt of a reverted transfer is refused, and a successful one has landed. */
internal fun evmLanding(receipt: EvmReceipt?): Landing = when {
    receipt == null -> Landing.PENDING
    receipt.success -> Landing.LANDED
    else -> Landing.REFUSED
}

/** Bitcoin: the first confirmation puts the coins in the recipient's wallet. */
internal fun bitcoinLanding(status: EsploraTxStatus): Landing = if (status.confirmed) Landing.LANDED else Landing.PENDING

/** Solana: a status that failed on chain is refused, and a confirmed status has landed. */
internal fun solanaLanding(status: SolanaStatus?): Landing = when {
    status == null -> Landing.PENDING
    status.failed -> Landing.REFUSED
    status.confirmed -> Landing.LANDED
    else -> Landing.PENDING
}

/**
 * Algorand: the pool reports the round that confirmed the transaction, and a pool error refuses it. A transaction the pool no longer holds reads as
 * pending: the pool's answer cannot tell a confirmed transaction from a dropped one.
 */
internal fun algorandLanding(info: AlgorandPendingInfo?): Landing = when {
    info == null -> Landing.PENDING
    info.poolError.isNotEmpty() -> Landing.REFUSED
    info.confirmedRound > 0 -> Landing.LANDED
    else -> Landing.PENDING
}
