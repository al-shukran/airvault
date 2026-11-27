package app.lumio.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.RandomAccessFile

/**
 * The database under the cache, the scan queue and the transaction history. Every value in it is sealed, is a keyed hash, or is a plain scalar that
 * names no wallet, such as a time, an amount or a network. The file lives in the vault's own folder, so a vault wipe deletes it with the rest of the vault.
 */
@Database(
    entities = [KvRow::class, ScanJobRow::class, TxRow::class, TxTermRow::class, TxComment::class],
    version = LumioDatabase.VERSION,
    exportSchema = false,
)
abstract class LumioDatabase : RoomDatabase() {
    abstract fun kv(): KvDao

    abstract fun scanJobs(): ScanJobDao

    abstract fun txs(): TxDao

    companion object {
        /** The file name of the database, inside the vault's folder. */
        const val FILE_NAME = "lumio-data.db"

        /**
         * The schema number. Lumio 1.0.0 wrote its database as number 2, before it zeroed deleted content, so a file of that number holds free pages
         * with the plain columns of deleted rows. Raising the number replaces those files at the next open (see [open]).
         */
        const val VERSION = 3

        /**
         * Opens the database in [file], creating the file now. The folder of [file] must exist already, which it does while a vault is open. The file
         * is created at once, so a later check that it still exists tells whether a vault wipe has removed it. A file of another schema number is
         * overwritten with zeros and deleted before Room sees it, so no migration runs and no page of an older build is left in a free page (the file system may keep the freed blocks until they are reused, as it does for every shredded file): the cache,
         * the scan queue and the history are read from the chains again, so no owner data is lost. A deleted row is overwritten with zeros, not left
         * in a free page: the rows of a replaced vault keep plain columns that must not outlive it. [configure] adjusts the builder, for tests.
         */
        fun open(context: Context, file: File, configure: RoomDatabase.Builder<LumioDatabase>.() -> Unit = {}): LumioDatabase {
            eraseIfOfAnotherVersion(file)
            return Room.databaseBuilder(context.applicationContext, LumioDatabase::class.java, file.absolutePath)
                .apply(configure)
                .build()
                .also { it.openHelper.writableDatabase.overwriteDeletedContent() }
        }

        /** Zeroes and deletes [file] and its log files when its schema number is not [VERSION], or when it cannot be read. */
        private fun eraseIfOfAnotherVersion(file: File) {
            if (!file.exists()) return
            val version = try {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
            } catch (_: RuntimeException) {
                -1
            }
            if (version == VERSION) return
            listOf("", "-journal", "-wal", "-shm").map { File(file.path + it) }.filter { it.exists() }.forEach {
                RandomAccessFile(it, "rw").use { raf ->
                    val zeros = ByteArray(ZERO_CHUNK)
                    var left = raf.length()
                    while (left > 0) {
                        raf.write(zeros, 0, minOf(left, zeros.size.toLong()).toInt())
                        left -= zeros.size
                    }
                    raf.fd.sync()
                }
                it.delete()
            }
        }

        private const val ZERO_CHUNK = 8192

        /** Turns `secure_delete` on for the connection that writes, inside a transaction, which a statement of this connection always joins. */
        private fun SupportSQLiteDatabase.overwriteDeletedContent() {
            beginTransaction()
            try {
                query("PRAGMA secure_delete=ON").use { it.moveToFirst() }
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }
}
