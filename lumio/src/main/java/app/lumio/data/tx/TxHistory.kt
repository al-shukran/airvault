package app.lumio.data.tx

import app.lumio.btc.EsploraTx
import app.lumio.data.Clock
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.AddressTxHistory
import app.lumio.data.provider.AddressTxs
import app.lumio.data.provider.MAX_TX_PAGE
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.TipHeight
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler

/**
 * Reads one account's history from the providers and stores it. An EVM history is one page of the explorers' normal transactions and token transfers.
 * A Bitcoin history is the Esplora address list. Each row becomes a [LumioTx], is flagged by [TxSpam] against the counterparties the store already
 * holds, and is written with [TxStore.put]. A Bitcoin refresh also removes the pending rows that the list no longer has. The Bitcoin scan hands its own
 * reads to [storeBitcoin], so a scan and a refresh store the same rows.
 *
 * With [preferRpc] on, no explorer is asked, as that setting promises, so an EVM history is refused with [ProviderError.Unsupported] rather than read
 * from an explorer. [nativeAsset] names the native coin of an EVM network from the registry. The Bitcoin coin is [BTC_ASSET].
 */
class TxHistory(
    private val scheduler: Scheduler,
    private val store: TxStore,
    private val spam: TxSpam,
    private val nativeAsset: (NetworkId) -> TxAsset?,
    private val preferRpc: () -> Boolean,
    private val clock: Clock,
) {
    /**
     * Reads [page] of the history of [address] on [net], and stores it. Returns the number of transactions read. A Bitcoin history has one page.
     * @throws ProviderError when the providers cannot serve the history now, and [ProviderError.Unsupported] when none can serve it at all.
     */
    suspend fun refresh(net: NetworkId, address: String, priority: Priority = Priority.VISIBLE, page: Int = 1): Int {
        require(page >= 1) { "A page counts from 1." }
        if (net.isBtc) {
            require(page == 1) { "A Bitcoin history has one page." }
            val tip = scheduler.run(TipHeight(net), priority)
            return storeBitcoin(net, address, scheduler.run(AddressTxs(net, address), priority), tip)
        }
        val fetched: List<LumioTx> = when {
            net.isEvm -> {
                if (preferRpc()) throw ProviderError.Unsupported()
                val native = nativeAsset(net) ?: throw ProviderError.Unsupported()
                scheduler.run(AddressTxHistory(net, address, page, MAX_TX_PAGE), priority).toLumioTxs(address, net, native)
            }
            else -> throw ProviderError.Unsupported()
        }
        return write(address, fetched)
    }

    /**
     * Stores the Bitcoin transactions [txs] that an Esplora server lists for [address], at chain tip [tipHeight]. A pending row that [txs] no longer
     * list is removed. Returns the number of transactions stored.
     */
    suspend fun storeBitcoin(net: NetworkId, address: String, txs: List<EsploraTx>, tipHeight: Long): Int {
        val observedAt = clock.nowMs()
        val fetched = txs.map { it.toLumioTx(address, net, tipHeight, observedAt) }
        val stored = write(address, fetched)
        store.dropPending(address, net, fetched.mapTo(HashSet()) { it.id })
        return stored
    }

    /** [fetched], flagged by the spam heuristic against the counterparties the store already holds, then written. Returns the number written. */
    private suspend fun write(address: String, fetched: List<LumioTx>): Int {
        val known = store.counterparties(address)
        val flagged = fetched.map { it.copy(spam = spam.isSpam(it, known)) }
        store.put(flagged)
        return flagged.size
    }
}
