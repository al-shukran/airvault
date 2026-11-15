package app.lumio.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
         * is created at once, so a later check that it still exists tells whether a vault wipe has removed it.
         */
        fun open(context: Context, file: File): LumioDatabase =
            Room.databaseBuilder(context.applicationContext, LumioDatabase::class.java, file.absolutePath)
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { it.openHelper.writableDatabase }

        /**
         * Version 2 adds the transaction history: its rows, their search terms and the comments. It creates the three tables and the one index that Room
         * expects, and nothing else changes, so the cache and the scan queue survive the upgrade. No comment exists before version 2.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tx` (`txId` BLOB NOT NULL, `accountKey` BLOB NOT NULL, `assetKey` BLOB NOT NULL, " +
                        "`network` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, `amountKey` TEXT NOT NULL, `feeKey` TEXT NOT NULL, " +
                        "`assetSymbol` TEXT NOT NULL, `direction` TEXT NOT NULL, `status` TEXT NOT NULL, `spam` INTEGER NOT NULL, " +
                        "`settled` INTEGER NOT NULL, `payload` BLOB NOT NULL, PRIMARY KEY(`txId`, `accountKey`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tx_accountKey` ON `tx` (`accountKey`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tx_term` (`txId` BLOB NOT NULL, `accountKey` BLOB NOT NULL, `term` BLOB NOT NULL, " +
                        "PRIMARY KEY(`txId`, `accountKey`, `term`))",
                )
                db.execSQL("CREATE TABLE IF NOT EXISTS `tx_comment` (`txId` BLOB NOT NULL, `text` BLOB NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`txId`))")
            }
        }
    }
}
