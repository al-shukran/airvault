package app.lumio.wallets

import app.lumio.btc.BtcAccountRef
import app.lumio.btc.networkId
import app.lumio.evm.EvmChain
import app.lumio.transactions.HistoryRead
import app.lumio.transactions.TxSyncPlan
import app.lumio.transactions.evmNetwork

/**
 * What an account's activity reads from the providers. An Ethereum account reads its address on each network it is given, and a Bitcoin account reads
 * its own scan. Solana and Algorand have no history provider yet, so they read nothing.
 */
internal object AccountActivity {
    /** The reads of [account]: its address on each of [chains], the enabled networks of the side, or its Bitcoin account [bitcoin] when it is one. */
    fun plan(account: WalletAccount, chains: List<EvmChain>, bitcoin: BtcAccountRef?): TxSyncPlan = when (account.chain) {
        AccountChain.ETHEREUM -> TxSyncPlan(
            networks = chains.mapTo(HashSet()) { evmNetwork(it) },
            evm = chains.map { HistoryRead(evmNetwork(it), account.address) },
            btc = emptyList(),
        )
        AccountChain.BITCOIN -> TxSyncPlan(
            networks = bitcoin?.let { setOf(it.account.network.networkId()) }.orEmpty(),
            evm = emptyList(),
            btc = listOfNotNull(bitcoin),
        )
        AccountChain.SOLANA, AccountChain.ALGORAND -> TxSyncPlan(networks = emptySet(), evm = emptyList(), btc = emptyList())
    }
}
