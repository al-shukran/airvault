package app.lumio.data.tx

import app.lensly.core.vault.VaultSession

/**
 * The sort that the owner chose last on the Transactions tab. It is a vault record, sealed under the vault key, so the tab opens in the same order.
 * A missing or unknown record reads as the newest first.
 */
object TxViewStore {
    /** The vault record that holds the sort. */
    const val RECORD = "lumio-tx-view"

    /** The sort key and whether it runs descending. */
    data class Sort(val sort: TxSort, val descending: Boolean)

    fun read(session: VaultSession): Sort {
        val text = session.getVaultRecord(RECORD)?.decodeToString().orEmpty()
        val key = text.substringBefore('|')
        return Sort(
            sort = TxSort.entries.firstOrNull { it.name == key } ?: TxSort.TIMESTAMP,
            descending = text.substringAfter('|', "desc") != "asc",
        )
    }

    fun write(session: VaultSession, sort: Sort) {
        val order = if (sort.descending) "desc" else "asc"
        session.putVaultRecord(RECORD, "${sort.sort.name}|$order".encodeToByteArray())
    }
}
