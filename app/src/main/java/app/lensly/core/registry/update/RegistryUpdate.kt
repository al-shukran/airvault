package app.lensly.core.registry.update

import app.lensly.core.registry.Registry
import app.lensly.core.vault.writeAtomically
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Offline catalog updates. A registry package adds networks and tokens to the baked-in catalog. It can never
 * change or remove a baked entry, and it is accepted only when the release key signed it.
 *
 * Packages arrive on removable storage. This object checks them before anything is stored. Reading them from
 * storage is the caller's job.
 */
object RegistryUpdate {
    /** APK asset holding the trusted Ed25519 public key as 64 hexadecimal characters. */
    const val TRUSTED_KEY_PATH = "registry/release.pub"

    /** The accepted package, stored in the registry directory (`noBackupFilesDir/registry/`). */
    const val INSTALLED_FILE = "installed.lrp"

    private val installLock = Any()

    private const val READ_CHUNK_BYTES = 8 * 1024

    /** The trusted keys: the single key in the [TRUSTED_KEY_PATH] asset, read through [readAsset]. */
    fun trustedKeys(readAsset: (String) -> String?): List<ByteArray> = listOf(
        PackageFormat.parsePublicKey(checkNotNull(readAsset(TRUSTED_KEY_PATH)) { "missing asset $TRUSTED_KEY_PATH" }),
    )

    /**
     * Checks [bytes] against [trustedPublicKeys] and the [baked] catalog without storing anything. The sequence
     * check belongs to [install], because a package can be valid on its own and still be an old one.
     */
    fun verify(bytes: ByteArray, trustedPublicKeys: List<ByteArray>, baked: Registry): Result<RegistryPackage> =
        runCatching {
            val pkg = PackageFormat.decode(PackageFormat.open(bytes, trustedPublicKeys))
            checkAgainstBaked(pkg, baked)
            pkg
        }

    /**
     * Reads a whole package from [input]. A stream longer than [PackageFormat.MAX_BYTES] is refused as
     * [RegistryUpdateException.Malformed] after one byte past the cap, so an oversized file is never buffered.
     */
    fun readPackage(input: InputStream): ByteArray {
        val limit = PackageFormat.MAX_BYTES + 1
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK_BYTES)
        // Same as InputStream.readNBytes(limit), which API 31 lacks: reads until [limit] bytes or end of stream.
        while (out.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        val bytes = out.toByteArray()
        if (bytes.size > PackageFormat.MAX_BYTES) {
            throw RegistryUpdateException.Malformed("larger than ${PackageFormat.MAX_BYTES} bytes")
        }
        return bytes
    }

    /**
     * Stores [bytes] as the installed package in [dir] when they verify and their sequence is higher than the
     * installed package's. An older or equal sequence is refused with [RegistryUpdateException.NotNewer]. On any
     * failure nothing is written.
     */
    fun install(
        bytes: ByteArray,
        trustedPublicKeys: List<ByteArray>,
        baked: Registry,
        dir: File,
    ): Result<RegistryPackage> = synchronized(installLock) {
        runCatching {
            val incoming = verify(bytes, trustedPublicKeys, baked).getOrThrow()
            val installed = installedSequence(dir, trustedPublicKeys)
            if (incoming.sequence <= installed) {
                throw RegistryUpdateException.NotNewer(installed, incoming.sequence)
            }
            check(dir.isDirectory || dir.mkdirs()) { "cannot create ${dir.path}" }
            writeAtomically(File(dir, INSTALLED_FILE), bytes)
            incoming
        }
    }

    /**
     * The [registry] with the installed package in [dir] merged in. Installed entries are only added. When there is
     * no installed package, or it no longer verifies against [registry] (for example because an app update bakes
     * one of its chain IDs), [registry] is returned unchanged.
     */
    fun withInstalled(registry: Registry, dir: File, trustedPublicKeys: List<ByteArray>): Registry {
        val file = File(dir, INSTALLED_FILE)
        if (!file.isFile) return registry
        val installed = runCatching { verify(file.readBytes(), trustedPublicKeys, registry).getOrThrow() }
            .getOrNull() ?: return registry
        return registry.withAdditions(installed.networks, installed.tokens)
    }

    /** Sequence of the installed package, or 0 when none is stored or the stored file no longer verifies. */
    private fun installedSequence(dir: File, trustedPublicKeys: List<ByteArray>): Long {
        val file = File(dir, INSTALLED_FILE)
        if (!file.isFile) return 0
        return runCatching {
            PackageFormat.decode(PackageFormat.open(file.readBytes(), trustedPublicKeys)).sequence
        }.getOrDefault(0)
    }

    private fun checkAgainstBaked(pkg: RegistryPackage, baked: Registry) {
        for (network in pkg.networks) {
            if (baked.network(network.chainId) != null) {
                throw RegistryUpdateException.Conflict("chain ${network.chainId} is already in the baked catalog")
            }
        }
        for (token in pkg.tokens) {
            if (baked.token(token.chainId, token.contract) != null) {
                throw RegistryUpdateException.Conflict(
                    "token ${token.contract} on chain ${token.chainId} is already in the baked catalog",
                )
            }
            val chainKnown = baked.network(token.chainId) != null || pkg.networks.any { it.chainId == token.chainId }
            if (!chainKnown) {
                throw RegistryUpdateException.InvalidEntry(
                    "token ${token.contract} is on chain ${token.chainId}, which is neither baked nor in the package",
                )
            }
        }
    }
}
