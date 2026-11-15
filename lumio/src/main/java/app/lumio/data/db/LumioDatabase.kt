package app.lumio.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The database under the cache and the scan queue. Every value in it is sealed, so the file holds no readable wallet data. It lives in the app's
 * private storage: [open] names a file in the databases folder.
 */
@Database(entities = [KvRow::class, ScanJobRow::class], version = 1, exportSchema = false)
abstract class LumioDatabase : RoomDatabase() {
    abstract fun kv(): KvDao

    abstract fun scanJobs(): ScanJobDao

    companion object {
        const val FILE_NAME = "lumio-data.db"

        /** Opens the database called [name] in the app's databases folder. */
        fun open(context: Context, name: String = FILE_NAME): LumioDatabase =
            Room.databaseBuilder(context.applicationContext, LumioDatabase::class.java, name).build()
    }
}
