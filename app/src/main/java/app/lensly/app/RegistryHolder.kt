package app.lensly.app

import android.content.Context
import android.content.res.AssetManager
import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.registry.update.RegistryCatalog
import app.lensly.core.registry.update.RegistryPackage
import app.lensly.core.registry.update.RegistryUpdate
import java.io.File
import java.io.FileNotFoundException

/**
 * The registry that sign requests are checked against: the baked catalogs, plus the installed package when it still
 * verifies. It is loaded on the first call and kept until the next accepted [install]. The first call reads the
 * assets, so call this off the main thread.
 */
object RegistryHolder {
    /** Directory under noBackupFilesDir where [RegistryUpdate.install] stores an accepted package. */
    private const val INSTALLED_DIR = "registry"

    private var catalog: RegistryCatalog? = null

    /** The registry that sign requests are checked against. */
    @Synchronized
    fun get(context: Context): Registry = catalogOf(context).current()

    /**
     * The installed package that [get] merges in, so its sequence is the catalog's version. Null while the built-in catalog is
     * all there is. The first call reads the assets, so call this off the main thread.
     */
    @Synchronized
    fun installed(context: Context): RegistryPackage? = catalogOf(context).installed()

    /**
     * Verifies [bytes] and stores them as the installed package. An accepted package is in effect for the next [get].
     * Call this off the main thread: it reads and writes storage.
     */
    @Synchronized
    fun install(context: Context, bytes: ByteArray): Result<RegistryPackage> = catalogOf(context).install(bytes)

    private fun catalogOf(context: Context): RegistryCatalog =
        catalog ?: load(context.applicationContext).also { catalog = it }

    private fun load(context: Context): RegistryCatalog {
        val assets = context.assets
        val baked = RegistryLoader.fromAssets(assets)
        val trustedKeys = RegistryUpdate.trustedKeys { path -> readAsset(assets, path) }
        return RegistryCatalog(baked, File(context.noBackupFilesDir, INSTALLED_DIR), trustedKeys)
    }

    private fun readAsset(assets: AssetManager, path: String): String? = try {
        assets.open(path).bufferedReader().use { it.readText() }
    } catch (e: FileNotFoundException) {
        null
    }
}
