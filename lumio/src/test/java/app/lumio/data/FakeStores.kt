package app.lumio.data

import app.lumio.data.db.KvDao
import app.lumio.data.db.KvRow
import app.lumio.data.db.ScanJobDao
import app.lumio.data.db.ScanJobRow

/** An in-memory [KvDao], for tests that need the cache's rules and not a database file. Rows keep their insertion order. */
class FakeKvDao : KvDao {
    private val rows = LinkedHashMap<String, KvRow>()

    private fun name(k: ByteArray): String = k.joinToString("") { "%02x".format(it) }

    override suspend fun get(k: ByteArray): KvRow? = rows[name(k)]

    override suspend fun all(): List<KvRow> = rows.values.toList()

    override suspend fun put(row: KvRow) {
        rows[name(row.k)] = row
    }

    override suspend fun touch(k: ByteArray, atMs: Long) {
        rows[name(k)]?.let { rows[name(k)] = KvRow(it.k, it.v, it.policy, atMs) }
    }

    override suspend fun delete(k: ByteArray) {
        rows.remove(name(k))
    }

    override suspend fun totalBytes(policy: Int): Long = rows.values.filter { it.policy == policy }.sumOf { it.v.size.toLong() }

    override suspend fun oldestKeyExcept(policy: Int, skip: List<ByteArray>): ByteArray? {
        val skipped = skip.map(::name).toSet()
        return rows.values.filter { it.policy == policy && name(it.k) !in skipped }.minByOrNull { it.atMs }?.k
    }
}

/** An in-memory [ScanJobDao], so the queue and the runner can be tested as one process, and reopened as another over the same rows. */
class FakeScanJobDao : ScanJobDao {
    private val rows = LinkedHashMap<Long, ScanJobRow>()
    private var nextId = 1L

    override suspend fun insert(row: ScanJobRow): Long {
        val id = nextId++
        rows[id] = ScanJobRow(id = id, payload = row.payload)
        return id
    }

    override suspend fun all(): List<ScanJobRow> = rows.values.toList()

    override suspend fun get(id: Long): ScanJobRow? = rows[id]

    override suspend fun updatePayload(id: Long, payload: ByteArray) {
        if (rows.containsKey(id)) rows[id] = ScanJobRow(id = id, payload = payload)
    }

    override suspend fun delete(id: Long) {
        rows.remove(id)
    }
}
