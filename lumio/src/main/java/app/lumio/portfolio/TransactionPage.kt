package app.lumio.portfolio

import app.lensly.app.AddressBookEntry
import app.lensly.app.InternalAccount
import app.lumio.pair.PairedWallet
import app.lumio.transactions.TxDetail

/**
 * One transaction as its page shows it: the detail, the wallet's own accounts by their address key, the wallet, and its address book. The row that opens
 * the page reads it first, so the page has everything it shows on its first frame.
 */
class TransactionPage(
    val detail: TxDetail,
    val accounts: Map<String, InternalAccount>,
    val wallet: PairedWallet?,
    val book: List<AddressBookEntry>,
)

/**
 * The transaction page that was read last. A page opened from a row takes it on its first frame when it is the same transaction. Any other transaction
 * is read again, and that read takes its place.
 */
internal class LastTransaction {
    private var page: TransactionPage? = null

    /** Keeps [page] as the last one read. */
    fun put(page: TransactionPage) {
        this.page = page
    }

    /** The last page read, when it is the transaction [txId] of [account], or null. */
    fun get(account: String, txId: String): TransactionPage? =
        page?.takeIf { it.detail.tx.account == account && it.detail.tx.id == txId }

    /** Forgets the last page. */
    fun clear() {
        page = null
    }
}
