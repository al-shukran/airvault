package app.lumio.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * One transaction in one account's history. [txId] and [accountKey] are keyed hashes, so no column names a transaction or an account. [payload] is the
 * whole transaction sealed under the vault's key, and it is the only place that holds the hash, the counterparty and the memo in the clear. The other
 * columns are what search, filter and sort need: the network, the time, [amountKey] and [feeKey] (see `app.lumio.data.tx.TxAmounts`), the asset symbol,
 * the direction and the status. [spam] is set by the spam heuristic. [settled] is set once the transaction is final, so a later read never replaces it.
 */
@Entity(tableName = "tx", primaryKeys = ["txId", "accountKey"], indices = [Index(value = ["accountKey"])])
class TxRow(
    val txId: ByteArray,
    val accountKey: ByteArray,
    val assetKey: ByteArray,
    val network: String,
    val timestampMs: Long,
    val amountKey: String,
    val feeKey: String,
    val assetSymbol: String,
    val direction: String,
    val status: String,
    val spam: Boolean,
    val settled: Boolean,
    val payload: ByteArray,
)

/**
 * One search term of a transaction row, or of a comment. A row's terms are keyed by its [txId] and [accountKey]. A comment's terms carry the comment's key
 * in [accountKey], so a search reads a row's terms and its comment's terms as one set. The term is a keyed hash of the word, so the index names no word.
 */
@Entity(tableName = "tx_term", primaryKeys = ["txId", "accountKey", "term"])
class TxTermRow(val txId: ByteArray, val accountKey: ByteArray, val term: ByteArray)

/**
 * A user's comment on one transaction. [txId] is the keyed hash of the transaction's id, and it is the key, so a comment is never evicted with the cache. [text]
 * is sealed: it holds the transaction's id and the comment text. [updatedAt] is the time the comment was last written, in epoch milliseconds.
 */
@Entity(tableName = "tx_comment")
class TxComment(
    @PrimaryKey val txId: ByteArray,
    val text: ByteArray,
    val updatedAt: Long,
)

/**
 * The transaction store's table, its term index and its comments. Every write that touches several of them runs in one transaction, so the index never
 * lags behind the rows that it names.
 */
@Dao
abstract class TxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertRow(row: TxRow)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTerms(terms: List<TxTermRow>)

    @Query("DELETE FROM tx_term WHERE txId = :txId AND accountKey = :accountKey")
    abstract suspend fun deleteTerms(txId: ByteArray, accountKey: ByteArray)

    @Query("DELETE FROM tx WHERE txId = :txId AND accountKey = :accountKey")
    abstract suspend fun deleteRow(txId: ByteArray, accountKey: ByteArray)

    @Query("SELECT * FROM tx WHERE txId = :txId AND accountKey = :accountKey")
    abstract suspend fun row(txId: ByteArray, accountKey: ByteArray): TxRow?

    @Query("SELECT * FROM tx WHERE accountKey = :accountKey")
    abstract suspend fun rowsOf(accountKey: ByteArray): List<TxRow>

    @Query("SELECT EXISTS(SELECT 1 FROM tx WHERE txId = :txId)")
    abstract suspend fun hasRow(txId: ByteArray): Boolean

    @RawQuery
    abstract suspend fun page(query: SupportSQLiteQuery): List<TxRow>

    @RawQuery
    abstract suspend fun count(query: SupportSQLiteQuery): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertComment(comment: TxComment)

    @Query("DELETE FROM tx_comment WHERE txId = :txId")
    abstract suspend fun deleteComment(txId: ByteArray)

    @Query("SELECT * FROM tx_comment WHERE txId = :txId")
    abstract suspend fun comment(txId: ByteArray): TxComment?

    @Query("SELECT * FROM tx_comment")
    abstract suspend fun comments(): List<TxComment>

    /** Writes [row] with [terms], replacing the terms the row had before. */
    @Transaction
    open suspend fun writeRow(row: TxRow, terms: List<TxTermRow>) {
        insertRow(row)
        deleteTerms(row.txId, row.accountKey)
        insertTerms(terms)
    }

    /** Deletes the row of [txId] for [accountKey], and its terms. */
    @Transaction
    open suspend fun removeRow(txId: ByteArray, accountKey: ByteArray) {
        deleteRow(txId, accountKey)
        deleteTerms(txId, accountKey)
    }

    /**
     * Stores [comment] for [txId], with [terms] under [commentKey], replacing the comment's earlier terms. A null [comment] clears it, and its terms with it.
     */
    @Transaction
    open suspend fun writeComment(txId: ByteArray, commentKey: ByteArray, comment: TxComment?, terms: List<TxTermRow>) {
        deleteTerms(txId, commentKey)
        deleteComment(txId)
        if (comment != null) insertComment(comment)
        insertTerms(terms)
    }
}
