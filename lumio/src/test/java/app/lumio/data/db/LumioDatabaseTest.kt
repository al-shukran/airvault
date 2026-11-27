package app.lumio.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.lumio.data.containsBytes
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The database file on real SQLite under Robolectric. Robolectric's SQLite zeroes deleted content by default, so each test that needs a deleted row to
 * stay in the file turns `secure_delete` off on the connection that writes it, and the tests then prove what the app does about it.
 */
@RunWith(RobolectricTestRunner::class)
class LumioDatabaseTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()
    private val file get() = File(tmp.root, "lumio-data.db")
    private val marker = "REMNANTMARKER".toByteArray()

    /** Builds [file] with a `tx` table of an older schema holding a row with [marker], at schema [version]. */
    private fun builtByAnEarlierBuild(version: Int) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE tx (id INTEGER PRIMARY KEY, assetSymbol TEXT)")
        db.execSQL("INSERT INTO tx (id, assetSymbol) VALUES (1, 'REMNANTMARKER')")
        db.version = version
        db.close()
    }

    /**
     * Builds [file] as the released Lumio did: the current tables, a row with [marker] that was deleted without zeroing, and the released schema number,
     * so a build that trusts the number keeps the row's bytes in the file.
     */
    private fun builtByTheReleasedBuild() {
        LumioDatabase.open(context, file).close()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.rawQuery("PRAGMA secure_delete=OFF", null).use { it.moveToFirst() }
        db.execSQL(
            "INSERT INTO tx VALUES (zeroblob(32), zeroblob(32), zeroblob(32), 'net', 1, 'a', 'f', 'REMNANTMARKER', 'IN', 'CONFIRMED', 0, 1, zeroblob(8))",
        )
        db.execSQL("DELETE FROM tx")
        db.version = RELEASED_SCHEMA
        db.close()
    }

    private fun assertMarkerGone() {
        listOf(file, File(tmp.root, "lumio-data.db-wal")).filter { it.exists() }
            .forEach { assertFalse(it.readBytes().containsBytes(marker), "${it.name} still holds the old row") }
    }

    private fun checkpoint(db: LumioDatabase) {
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
    }

    @Test
    fun aFileOfAnOlderSchemaIsReplacedWithoutLeavingItsRowsInAFreePage() {
        builtByAnEarlierBuild(version = 1)
        assertEquals(true, file.readBytes().containsBytes(marker), "the fixture holds the marker")

        val db = LumioDatabase.open(context, file)
        checkpoint(db)
        db.close()

        assertMarkerGone()
    }

    @Test
    fun aFileOfTheReleasedSchemaWithADeletedRowInAFreePageIsCleared() {
        builtByTheReleasedBuild()
        assertEquals(true, file.readBytes().containsBytes(marker), "the free page of the fixture holds the marker")

        val db = LumioDatabase.open(context, file)
        checkpoint(db)
        db.close()

        assertMarkerGone()
    }

    @Test
    fun theDatabaseZeroesWhatItDeletesEvenWhenTheConnectionStartedWithItOff() = runBlocking {
        val db = LumioDatabase.open(context, file) {
            addCallback(
                object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.query("PRAGMA secure_delete=OFF").use { it.moveToFirst() }
                    }
                },
            )
        }
        val row = TxRow(
            txId = ByteArray(32) { 1 }, accountKey = ByteArray(32) { 2 }, assetKey = ByteArray(32) { 3 }, network = "net", timestampMs = 1L,
            amountKey = "a", feeKey = "f", assetSymbol = "REMNANTMARKER", direction = "IN", status = "CONFIRMED", spam = false, settled = true,
            payload = ByteArray(8),
        )
        db.txs().insertRow(row)
        checkpoint(db)
        assertEquals(true, file.readBytes().containsBytes(marker), "the row is in the file before the delete")

        db.txs().deleteRow(row.txId, row.accountKey)
        checkpoint(db)
        db.close()

        assertMarkerGone()
    }

    private companion object {
        /** The schema number of Lumio 1.0.0, whose database was written without `secure_delete`. */
        const val RELEASED_SCHEMA = 2
    }
}
