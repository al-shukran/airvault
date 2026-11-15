package app.lumio.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One cache entry. [k] is the HMAC that names it, [v] is its sealed value, [policy] is the policy code, and [atMs] is when it was stored or,
 * for the immutable policy, last read.
 */
@Entity(tableName = "kv")
class KvRow(
    @PrimaryKey val k: ByteArray,
    val v: ByteArray,
    val policy: Int,
    val atMs: Long,
)

@Dao
interface KvDao {
    @Query("SELECT * FROM kv WHERE k = :k")
    suspend fun get(k: ByteArray): KvRow?

    @Query("SELECT * FROM kv")
    suspend fun all(): List<KvRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: KvRow)

    @Query("UPDATE kv SET atMs = :atMs WHERE k = :k")
    suspend fun touch(k: ByteArray, atMs: Long)

    @Query("DELETE FROM kv WHERE k = :k")
    suspend fun delete(k: ByteArray)

    @Query("SELECT COALESCE(SUM(LENGTH(v)), 0) FROM kv WHERE policy = :policy")
    suspend fun totalBytes(policy: Int): Long

    @Query("SELECT k FROM kv WHERE policy = :policy ORDER BY atMs ASC, rowid ASC LIMIT 1")
    suspend fun oldestKey(policy: Int): ByteArray?
}

/** One queued scan job. [payload] is the job sealed under the queue's key. [id] is set by the database. */
@Entity(tableName = "scan_job")
class ScanJobRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val payload: ByteArray,
)

@Dao
interface ScanJobDao {
    @Insert
    suspend fun insert(row: ScanJobRow): Long

    @Query("SELECT * FROM scan_job ORDER BY id ASC")
    suspend fun all(): List<ScanJobRow>

    @Query("SELECT * FROM scan_job WHERE id = :id")
    suspend fun get(id: Long): ScanJobRow?

    @Query("UPDATE scan_job SET payload = :payload WHERE id = :id")
    suspend fun updatePayload(id: Long, payload: ByteArray)

    @Query("DELETE FROM scan_job WHERE id = :id")
    suspend fun delete(id: Long)
}
