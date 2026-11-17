package app.lumio.transactions

import app.lumio.LumioApp
import app.lumio.LumioVault
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.networkId
import app.lumio.btc.scanAccount
import app.lumio.data.net.NetworkId
import app.lumio.data.sched.Priority
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettings
import app.lumio.evm.Portfolio
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallet
import kotlinx.coroutines.CancellationException

/** One address to read the history of, on one network. */
data class HistoryRead(val net: NetworkId, val address: String)

/**
 * The reads of the Transactions tab for one side. [networks] are the networks the feed shows. [evm] is each address of each paired wallet on each enabled
 * EVM chain of the side, and [btc] is each Bitcoin account of the side.
 */
data class TxSyncPlan(val networks: Set<NetworkId>, val evm: List<HistoryRead>, val btc: List<BtcAccountRef>)

/** The plan of the Transactions tab, and the reads that fill the store from it. */
object TxSync {
    /**
     * The plan for [mode]'s side. It holds the enabled EVM chains of that side, each address of each wallet on them, and the Bitcoin accounts of that side.
     * A chain or an account of the other side never appears in it, so a mainnet and a testnet history are never read together.
     */
    fun plan(
        chains: List<EvmChain>,
        settings: EvmSettings,
        mode: NetworkMode,
        wallets: List<PairedWallet>,
        bitcoin: List<BtcAccountRef>,
    ): TxSyncPlan {
        val side = chains.filter { it.chainId in settings.enabled && mode.holds(it) }
        val evm = side.flatMap { chain ->
            wallets.flatMap { wallet ->
                Portfolio.addresses(wallet.evmKeys, settings.addressCount).map { HistoryRead(evmNetwork(chain), it) }
            }
        }
        val networks = side.mapTo(HashSet()) { evmNetwork(it) } + mode.btcNetwork().networkId()
        return TxSyncPlan(networks = networks, evm = evm, btc = bitcoin.filter { mode.holds(it.account.network) })
    }

    /**
     * Reads [plan] into the store of [vault], one read after another, so that the scheduler's rate limits hold. [onStored] runs after each read, so the
     * feed shows what has landed. A read that fails is skipped, and the store keeps what it held. Each Bitcoin account is scanned in detail, which reads
     * the transactions of every address it uses. [onAddress] names each Bitcoin address that a scan turns up, as the scan reads it. Call it off the
     * main thread.
     */
    suspend fun run(app: LumioApp, vault: LumioVault, plan: TxSyncPlan, onStored: () -> Unit, onAddress: (String) -> Unit = {}) {
        val history = app.txHistory(vault)
        for (read in plan.evm) {
            attempt { history.refresh(read.net, read.address) }
            onStored()
        }
        for (ref in plan.btc) {
            val net = ref.account.network.networkId()
            attempt {
                scanAccount(
                    ref = ref,
                    scheduler = app.scheduler,
                    priority = Priority.VISIBLE,
                    nowMs = System.currentTimeMillis(),
                    memo = app.addressStats.forNetwork(net),
                    detail = true,
                    onTxs = { address, txs, tipHeight ->
                        onAddress(address)
                        history.storeBitcoin(net, address, txs, tipHeight)
                        onStored()
                    },
                )
            }
            onStored()
        }
    }

    /** Runs [block]. A failure is a read that did not answer, so it is skipped; a cancellation is passed on, so a screen that leaves stops its reads. */
    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The read did not answer. The store keeps what it held, and the next read goes on.
        }
    }
}
