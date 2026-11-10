package app.lensly.app

import android.content.Context
import android.content.res.AssetManager
import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.registry.update.RegistryUpdate
import java.io.File
import java.io.FileNotFoundException

/**
 * The registry that sign requests are checked against: the baked catalogs, plus the installed package when it still
 * verifies. It is loaded on the first call and kept for the life of the process. The first call reads the assets, so
 * call this off the main thread.
 */
object RegistryHolder {
    /** Directory under noBackupFilesDir where [RegistryUpdate.install] stores an accepted package. */
    private const val INSTALLED_DIR = "registry"

    private var loaded: Registry? = null

    @Synchronized
    fun get(context: Context): Registry = loaded ?: load(context.applicationContext).also { loaded = it }

    private fun load(context: Context): Registry {
        val assets = context.assets
        val baked = RegistryLoader.fromAssets(assets)
        val trustedKeys = RegistryUpdate.trustedKeys { path -> readAsset(assets, path) }
        return RegistryUpdate.withInstalled(baked, File(context.noBackupFilesDir, INSTALLED_DIR), trustedKeys)
    }

    private fun readAsset(assets: AssetManager, path: String): String? = try {
        assets.open(path).bufferedReader().use { it.readText() }
    } catch (e: FileNotFoundException) {
        null
    }
}
