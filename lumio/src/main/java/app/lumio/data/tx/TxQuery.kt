package app.lumio.data.tx

import app.lumio.data.net.NetworkId
import java.math.BigDecimal

/** The fields that a history can be sorted by. [column] is the SQL expression over the table's alias `t`. */
enum class TxSort(internal val column: String) {
    TIMESTAMP("t.timestampMs"),
    AMOUNT("t.amountKey"),
    FEE("t.feeKey"),
    ASSET("t.assetSymbol COLLATE NOCASE"),
    DIRECTION("t.direction"),
    STATUS("t.status"),
    NETWORK("t.network"),
}

/** One asset: the native coin of [network] when [contract] is null, or an ERC-20 token of that network at [contract]. */
data class TxAssetRef(val network: NetworkId, val contract: String? = null)

/**
 * A search, a filter and a sort over the history. A field left at its default does not filter.
 * - [accounts] limits the history to these accounts. Empty means every account.
 * - [text] is a search over the hash, the counterparty, the asset, the memo and the comments. Each word of it must match one of them.
 * - [amountAbove] and [amountBelow] compare whole units, and both bounds are exclusive. They compare on the same scale for every coin.
 * - [fromMs] is inclusive and [untilMs] is exclusive.
 * - Spam is left out unless [includeSpam] is set.
 * The sort is stable: rows that tie keep the order of their ids, so a page never shows a row twice.
 */
data class TxQuery(
    val accounts: Set<String> = emptySet(),
    val networks: Set<NetworkId> = emptySet(),
    val assets: Set<TxAssetRef> = emptySet(),
    val fromMs: Long? = null,
    val untilMs: Long? = null,
    val directions: Set<TxDirection> = emptySet(),
    val statuses: Set<TxStatus> = emptySet(),
    val amountAbove: BigDecimal? = null,
    val amountBelow: BigDecimal? = null,
    val text: String = "",
    val includeSpam: Boolean = false,
    val sort: TxSort = TxSort.TIMESTAMP,
    val descending: Boolean = true,
)
