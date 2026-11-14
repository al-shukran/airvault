package app.lensly.ui

import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import java.io.File

/**
 * The catalog that ships in the APK, read from the assets of :shared. Unit tests run from :app or from :shared, so the directory is
 * looked up from either working directory.
 */
fun shippedRegistry(): Registry = RegistryLoader.load { path ->
    shippedAssets.resolve(path).takeIf(File::isFile)?.readText()
}

private val shippedAssets: File = listOf(File("src/main/assets"), File("../shared/src/main/assets"))
    .firstOrNull { File(it, "registry/networks.json").isFile } ?: File("src/main/assets")
