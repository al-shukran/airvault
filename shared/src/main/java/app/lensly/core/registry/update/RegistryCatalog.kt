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
    private val trustedKeys: List<TrustedKey>,
) {
    private var merged: Registry = RegistryUpdate.withInstalled(baked, dir, trustedKeys)

    /** The baked catalog with the installed package merged in. */
    @Synchronized
    fun current(): Registry = merged

    /**
     * The installed package when it is in effect: it verifies, and its sequence is higher than the built-in catalog's, as the
     * whitelist rule requires. Otherwise null, and the built-in sequence is the one in effect. Synchronized like [install].
     */
    @Synchronized
    fun installed(): RegistryPackage? =
        RegistryUpdate.installedPackage(baked, dir, trustedKeys)?.takeIf { it.sequence > baked.catalogSequence }

    /**
     * Installs [bytes] as [RegistryUpdate.install] does, checking them against the baked catalog. On success the
     * merged view is rebuilt before this returns. On failure the current view is unchanged.
     */
    @Synchronized
    fun install(bytes: ByteArray): Result<RegistryPackage> =
        RegistryUpdate.install(bytes, trustedKeys, baked, dir).onSuccess {
            merged = RegistryUpdate.withInstalled(baked, dir, trustedKeys)
        }
}
