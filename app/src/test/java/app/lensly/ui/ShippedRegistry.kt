package app.lensly.ui

import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import java.io.File

/** The catalog that ships in the APK, read from its assets. Paths are relative to :app, the working directory for unit tests. */
internal fun shippedRegistry(): Registry = RegistryLoader.load { path ->
    File("src/main/assets", path).takeIf(File::isFile)?.readText()
}
