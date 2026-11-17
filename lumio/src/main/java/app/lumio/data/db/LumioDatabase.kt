package app.lumio.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/**
 * The database under the cache, the scan queue and the transaction history. Every value in it is sealed, is a keyed hash, or is a plain scalar that
 * names no wallet, such as a time, an amount or a network. The file lives in the vault's own folder, so a vault wipe deletes it with the rest of the vault.
 */
@Database(
    entities = [KvRow::class, ScanJobRow::class, TxRow::class, TxTermRow::class, TxComment::class],
    version = 2,
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
         * Opens the database in [file], creating the file now. The folder of [file] must exist already, which it does while a vault is open. The file
         * is created at once, so a later check that it still exists tells whether a vault wipe has removed it. A file of another schema version is
         * dropped and created again: the cache, the scan queue and the history are read from the chains again, so no owner data is lost.
         */
        fun open(context: Context, file: File): LumioDatabase =
            Room.databaseBuilder(context.applicationContext, LumioDatabase::class.java, file.absolutePath)
                .fallbackToDestructiveMigration()
                .build()
                .also { it.openHelper.writableDatabase }

    }
}
