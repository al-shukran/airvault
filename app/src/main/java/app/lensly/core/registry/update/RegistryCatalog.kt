package app.lensly.core.registry.update

import app.lensly.core.registry.Registry
import java.io.File

/**
 * The catalog that sign requests are checked against: the [baked] registry plus the installed package in [dir].
 * The merged view is built when the catalog is created. [install] rebuilds it after an accepted package, so the
 * new entries count at once, without a restart.
 */
class RegistryCatalog(
    private val baked: Registry,
    private val dir: File,
    private val trustedPublicKeys: List<ByteArray>,
) {
    private var merged: Registry = RegistryUpdate.withInstalled(baked, dir, trustedPublicKeys)

    /** The baked catalog with the installed package merged in. */
    @Synchronized
    fun current(): Registry = merged

    /**
     * Installs [bytes] as [RegistryUpdate.install] does, checking them against the baked catalog. On success the
     * merged view is rebuilt before this returns. On failure the current view is unchanged.
     */
    @Synchronized
    fun install(bytes: ByteArray): Result<RegistryPackage> =
        RegistryUpdate.install(bytes, trustedPublicKeys, baked, dir).onSuccess {
            merged = RegistryUpdate.withInstalled(baked, dir, trustedPublicKeys)
        }
}
