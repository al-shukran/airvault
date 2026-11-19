package app.lumio.data.provider

import app.lumio.data.net.NetworkId
import app.lumio.data.provider.explorer.ExplorerTx

/** The most rows one list of an explorer's answer asks for. A page this size stays far below the 4 MiB cap on an answer. */
const val MAX_TX_PAGE = 100

private val EVM_ADDRESS = Regex("0x[0-9a-fA-F]{40}")

/**
 * One page of the transaction history of an EVM [address], newest first. It is the normal transactions of the page, then its ERC-20 transfers, as an
 * explorer lists them. [page] counts from 1, and [offset] is the number of rows per page, at most [MAX_TX_PAGE]. A provider answers with both lists or
 * with an error, never with one of them, so a transfer is never shown without the transaction that carries it.
 *
 * The package is `app.lumio.data.provider`, because [Request] is sealed, and its subclasses must share its package.
 */
class AddressTxHistory(net: NetworkId, val address: String, val page: Int, val offset: Int) : Request<List<ExplorerTx>>(net, Ops.EVM_TX_HISTORY) {
    init {
        require(EVM_ADDRESS.matches(address)) { "Not an EVM address." }
        require(page >= 1) { "A page counts from 1." }
        require(offset in 1..MAX_TX_PAGE) { "A page holds 1 to $MAX_TX_PAGE rows." }
    }
}
