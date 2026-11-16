package app.lumio.data.db

import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The upgrade from version 1 of the database, as the released build wrote it, to version 2 with the transaction history. The cache must survive the
 * upgrade, and the new tables must be there and usable afterwards.
 */
@RunWith(RobolectricTestRunner::class)
class LumioDatabaseMigrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()

    /** Room's identity hash of version 1, as the generated code of the released build names it. */
    private val versionOneHash = "05023cda04f79917f03117bc369ce0b1"

    /** Writes a database file in the version-1 layout: the cache, the scan queue, one cache row, and Room's version-1 identity. */
    private fun writeVersionOne(file: File) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE IF NOT EXISTS `kv` (`k` BLOB NOT NULL, `v` BLOB NOT NULL, `policy` INTEGER NOT NULL, `atMs` INTEGER NOT NULL, PRIMARY KEY(`k`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `scan_job` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `payload` BLOB NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$versionOneHash')")
        db.execSQL("INSERT INTO kv (k, v, policy, atMs) VALUES (x'01', x'02', 0, 1000)")
        db.version = 1
        db.close()
    }

    @Test
    fun aVersionOneDatabaseOpensAsVersionTwoKeepsItsCacheAndHasTheTransactionTables() = runBlocking {
        val file = File(tmp.root, "v1.db")
        writeVersionOne(file)

        val database = LumioDatabase.open(context, file)

        assertNotNull(database.kv().get(byteArrayOf(1)))
        assertEquals(0, database.txs().rowsOf(ByteArray(32)).size)
        assertEquals(0, database.txs().comments().size)
        database.close()
    }
}
