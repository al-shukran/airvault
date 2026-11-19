package app.lumio.data.tx

import androidx.sqlite.db.SimpleSQLiteQuery
import app.lensly.core.vault.Aead
import app.lumio.data.db.TxComment
import app.lumio.data.db.TxDao
import app.lumio.data.db.TxRow
import app.lumio.data.db.TxTermRow
import app.lumio.data.net.NetworkId
import java.math.BigInteger
import java.security.GeneralSecurityException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A comment as the store reads it: the transaction it is on, its text, and the time it was written in epoch milliseconds. */
data class TxNote(val txId: String, val text: String, val updatedAt: Long)

/**
 * The transaction history of the open vault, in Room: one row per transaction per account, the terms that search reads, and the comments.
 *
 * The row's payload is the whole [LumioTx], sealed. Its name, and every account, asset and word the index names, is a keyed hash, so the database holds
 * no transaction id, address, memo or comment in the clear. The plain columns are the scalars that filter and sort need. [put] never replaces a settled
 * row, that is, one that is final on its network, so a later read cannot change it. A pending row is replaced by its newer form. [dropPending] removes the
 * pending rows that the network no longer lists, and no settled row.
 *
 * [page] and [count] apply a [TxQuery]. Each search word must match a term of the transaction or of its comment. Sorting is stable, so paging never repeats a row.
 */
class TxStore(private val dao: TxDao, private val keys: TxKeys) {
    /** Writes [txs]. Each transaction is stored under its id and its account, and a settled one is left as it is. */
    suspend fun put(txs: List<LumioTx>) {
        for (tx in txs) {
            val txId = keys.name(TX_LABEL, tx.id)
            val accountKey = accountKeyOf(tx.account)
            if (dao.row(txId, accountKey)?.settled == true) continue
            val terms = termsOf(tx).map { TxTermRow(txId, accountKey, keys.name(TERM_LABEL, it)) }
            dao.writeRow(rowOf(tx, txId, accountKey), terms)
        }
    }

    /**
     * Removes the pending transactions of [account] on [network] whose ids are not in [keep]. A transaction that the network has since confirmed, or
     * that is settled, is not removed here: only a pending row that the network no longer lists goes.
     */
    suspend fun dropPending(account: String, network: NetworkId, keep: Set<String>) {
        val accountKey = accountKeyOf(account)
        val net = networkKey(network)
        val kept = keep.mapTo(HashSet()) { keys.name(TX_LABEL, it).asList() }
        for (row in dao.rowsOf(accountKey)) {
            if (row.network == net && row.status == PENDING && row.txId.asList() !in kept) dao.removeRow(row.txId, row.accountKey)
        }
    }

    /** The counterparties of the account's transactions that are not spam: the addresses the account has dealt with. */
    suspend fun counterparties(account: String): Set<String> =
        dao.rowsOf(accountKeyOf(account)).asSequence().filter { !it.spam }.mapNotNull { open(it)?.counterparty }.toSet()

    /** The transactions that [query] matches, in its order, from [offset] on, at most [limit] of them. */
    suspend fun page(query: TxQuery, offset: Int, limit: Int): List<LumioTx> {
        require(offset >= 0 && limit >= 1) { "A page starts at 0 or later and holds at least one row." }
        val args = ArrayList<Any?>()
        val where = where(query, args)
        val order = if (query.descending) "DESC" else "ASC"
        args += limit.toLong()
        args += offset.toLong()
        val sql = "SELECT t.* FROM tx t WHERE $where ORDER BY ${query.sort.column} $order, t.txId ASC, t.accountKey ASC LIMIT ? OFFSET ?"
        return dao.page(SimpleSQLiteQuery(sql, args.toTypedArray())).mapNotNull { open(it) }
    }

    /** The number of transactions that [query] matches. */
    suspend fun count(query: TxQuery): Int {
        val args = ArrayList<Any?>()
        val where = where(query, args)
        return dao.count(SimpleSQLiteQuery("SELECT COUNT(*) FROM tx t WHERE $where", args.toTypedArray()))
    }

    /** Sets the comment on [txId] to [text], stamped [updatedAt]. A blank [text] clears the comment. */
    suspend fun setComment(txId: String, text: String, updatedAt: Long) {
        if (text.isBlank()) return clearComment(txId)
        val id = keys.name(TX_LABEL, txId)
        val payload = json.encodeToString(CommentPayload.serializer(), CommentPayload(txId, text)).encodeToByteArray()
        val sealed = Aead.seal(keys.encKey, commentAad(id), payload)
        val terms = TxTerms.of(text).map { TxTermRow(id, COMMENT_KEY, keys.name(TERM_LABEL, it)) }
        dao.writeComment(id, COMMENT_KEY, TxComment(id, sealed, updatedAt), terms)
    }

    /** Removes the comment on [txId], and its search terms. */
    suspend fun clearComment(txId: String) {
        dao.writeComment(keys.name(TX_LABEL, txId), COMMENT_KEY, comment = null, terms = emptyList())
    }

    /** The comment on [txId], or null when there is none. */
    suspend fun comment(txId: String): TxNote? = dao.comment(keys.name(TX_LABEL, txId))?.let { openComment(it) }

    /** The transaction [txId] of [account] as the store holds it, or null when it is not there or does not open. */
    suspend fun tx(account: String, txId: String): LumioTx? =
        dao.row(keys.name(TX_LABEL, txId), accountKeyOf(account))?.let { open(it) }

    /** Every comment in the store. A comment that does not open under the key is left out. */
    suspend fun comments(): List<TxNote> = dao.comments().mapNotNull { openComment(it) }

    private fun rowOf(tx: LumioTx, txId: ByteArray, accountKey: ByteArray): TxRow = TxRow(
        txId = txId,
        accountKey = accountKey,
        assetKey = keys.name(ASSET_LABEL, assetNameOf(TxAssetRef(tx.network, tx.asset.contract))),
        network = networkKey(tx.network),
        timestampMs = tx.timestampMs,
        amountKey = TxAmounts.key(tx.amount, tx.asset.decimals),
        feeKey = TxAmounts.key(tx.fee ?: BigInteger.ZERO, feeDecimals(tx.network)),
        assetSymbol = tx.asset.symbol,
        direction = tx.direction.name,
        status = tx.status.name,
        spam = tx.spam,
        settled = tx.status != TxStatus.PENDING && tx.confirmations >= finalityOf(tx.network),
        payload = Aead.seal(keys.encKey, rowAad(txId, accountKey), json.encodeToString(LumioTx.serializer(), tx).encodeToByteArray()),
    )

    /** The words that a transaction is found by: its hash, its counterparty, its asset's symbol and name, and its memo. */
    private fun termsOf(tx: LumioTx): Set<String> =
        TxTerms.of(tx.hash) + TxTerms.of(tx.counterparty) + TxTerms.of(tx.asset.symbol) + TxTerms.of(tx.asset.name) + TxTerms.of(tx.memo)

    /** The SQL of [query] over the table's alias `t`, with its values appended to [args] in order. */
    private fun where(query: TxQuery, args: MutableList<Any?>): String {
        val clauses = ArrayList<String>()
        if (query.accounts.isNotEmpty()) {
            clauses += "t.accountKey IN (${marks(query.accounts.size)})"
            query.accounts.forEach { args += accountKeyOf(it) }
        }
        if (query.networks.isNotEmpty()) {
            clauses += "t.network IN (${marks(query.networks.size)})"
            query.networks.forEach { args += networkKey(it) }
        }
        if (query.assets.isNotEmpty()) {
            clauses += "t.assetKey IN (${marks(query.assets.size)})"
            query.assets.forEach { args += keys.name(ASSET_LABEL, assetNameOf(it)) }
        }
        query.fromMs?.let {
            clauses += "t.timestampMs >= ?"
            args += it
        }
        query.untilMs?.let {
            clauses += "t.timestampMs < ?"
            args += it
        }
        if (query.directions.isNotEmpty()) {
            clauses += "t.direction IN (${marks(query.directions.size)})"
            query.directions.forEach { args += it.name }
        }
        if (query.statuses.isNotEmpty()) {
            clauses += "t.status IN (${marks(query.statuses.size)})"
            query.statuses.forEach { args += it.name }
        }
        query.amountAbove?.let {
            clauses += "t.amountKey > ?"
            args += TxAmounts.keyOf(it)
        }
        query.amountBelow?.let {
            clauses += "t.amountKey < ?"
            args += TxAmounts.keyOf(it)
        }
        if (!query.includeSpam) clauses += "t.spam = 0"
        val words = TxTerms.of(query.text)
        if (words.isNotEmpty()) {
            // A word matches when the row's own terms or its comment's terms hold it. Every word must match, so the count of matched words is the number of words.
            clauses += "(SELECT COUNT(DISTINCT x.term) FROM tx_term x WHERE x.txId = t.txId " +
                "AND (x.accountKey = t.accountKey OR x.accountKey = ?) AND x.term IN (${marks(words.size)})) = ?"
            args += COMMENT_KEY
            words.forEach { args += keys.name(TERM_LABEL, it) }
            args += words.size.toLong()
        }
        return clauses.joinToString(" AND ").ifEmpty { "1" }
    }

    /** The row of [row] in the clear, or null when the payload does not open under the key or does not decode. */
    private fun open(row: TxRow): LumioTx? {
        val plain = try {
            Aead.open(keys.encKey, rowAad(row.txId, row.accountKey), row.payload)
        } catch (e: GeneralSecurityException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        return try {
            json.decodeFromString(LumioTx.serializer(), plain.decodeToString())
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun openComment(row: TxComment): TxNote? {
        val plain = try {
            Aead.open(keys.encKey, commentAad(row.txId), row.text)
        } catch (e: GeneralSecurityException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        val payload = try {
            json.decodeFromString(CommentPayload.serializer(), plain.decodeToString())
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        return TxNote(payload.txId, payload.text, row.updatedAt)
    }

    private fun accountKeyOf(account: String): ByteArray =
        keys.name(ACCOUNT_LABEL, if (account.startsWith("0x")) account.lowercase() else account)

    private fun assetNameOf(ref: TxAssetRef): String = "${networkKey(ref.network)}|${ref.contract?.lowercase() ?: NATIVE}"

    private fun rowAad(txId: ByteArray, accountKey: ByteArray): ByteArray = ROW_AAD + txId + accountKey

    private fun commentAad(txId: ByteArray): ByteArray = COMMENT_AAD + txId

    /** The payload of a comment: the transaction it is on, and its text. Its time is a column of its own. */
    @Serializable
    private class CommentPayload(val txId: String, val text: String)

    companion object {
        private const val PENDING = "PENDING"
        private const val NATIVE = "native"
        private const val TX_LABEL = "tx"
        private const val ACCOUNT_LABEL = "account"
        private const val ASSET_LABEL = "asset"
        private const val TERM_LABEL = "term"

        /** The key that a comment's terms are filed under. No keyed hash is all zeros, so it never names a row. */
        private val COMMENT_KEY = ByteArray(32)
        private val ROW_AAD = "lumio-tx-row-v1".toByteArray(Charsets.UTF_8)
        private val COMMENT_AAD = "lumio-tx-comment-v1".toByteArray(Charsets.UTF_8)
        private val json = Json { ignoreUnknownKeys = true }

        /** The confirmations at which a transaction on [net] is final: 6 on Bitcoin, and 64 on an EVM network, as the design sets them. */
        internal fun finalityOf(net: NetworkId): Long = if (net.isBtc) 6L else 64L

        /** The decimals of a network's native coin: 8 on Bitcoin, and 18 on every EVM chain that Lumio lists, the registry's and the custom ones. */
        internal fun feeDecimals(net: NetworkId): Int = if (net.isBtc) 8 else 18

        /** The network's name in the table: its family and its chain. */
        internal fun networkKey(net: NetworkId): String = "${net.idPrefix}:${net.name}"

        private fun marks(n: Int): String = List(n) { "?" }.joinToString(", ")
    }
}
