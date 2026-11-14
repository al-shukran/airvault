package app.lensly.core.registry.update

import app.lensly.core.registry.Registry
import app.lensly.core.vault.writeAtomically
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Offline catalog updates. A registry package adds networks and tokens to the baked-in catalog. It can never
 * change or remove a baked entry. Its whitelist is the effective one only when the package is newer by sequence than the
 * build's catalog; otherwise the build's whitelist stays. A package is accepted only when a key the build trusts signed it
 * (see [TrustedKeys]), and only when its sequence is higher than every sequence accepted before.
 *
 * Packages arrive on removable storage. This object checks them before anything is stored. Reading them from
 * storage is the caller's job.
 */
object RegistryUpdate {
    /** The accepted package, stored in the registry directory (`noBackupFilesDir/registry/`). */
    const val INSTALLED_FILE = "installed.lrp"

    /**
     * The highest sequence ever accepted, as decimal text, stored beside [INSTALLED_FILE]. It keeps the rollback check
     * working when the installed package itself can no longer be read.
     */
    const val SEQUENCE_FILE = "accepted-sequence"

    private val installLock = Any()

    private const val READ_CHUNK_BYTES = 8 * 1024

    /**
     * Checks [bytes] against [trustedKeys] and the [baked] catalog without storing anything. The sequence
     * check belongs to [install], because a package can be valid on its own and still be an old one.
     */
    fun verify(bytes: ByteArray, trustedKeys: List<TrustedKey>, baked: Registry): Result<RegistryPackage> =
        runCatching {
            val pkg = PackageFormat.read(bytes, trustedKeys)
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
     * Stores [bytes] as the installed package in [dir] when they verify and their sequence is higher than the last
     * accepted one. An older or equal sequence is refused with [RegistryUpdateException.NotNewer]. On any failure
     * nothing is written.
     */
    fun install(
        bytes: ByteArray,
        trustedKeys: List<TrustedKey>,
        baked: Registry,
        dir: File,
    ): Result<RegistryPackage> = synchronized(installLock) {
        runCatching {
            val incoming = verify(bytes, trustedKeys, baked).getOrThrow()
            val accepted = acceptedSequence(dir, trustedKeys)
            if (incoming.sequence <= accepted) {
                throw RegistryUpdateException.NotNewer(accepted, incoming.sequence)
            }
            check(dir.isDirectory || dir.mkdirs()) { "cannot create ${dir.path}" }
            // The package goes first: if the record write is lost, the package still gives its own sequence.
            writeAtomically(File(dir, INSTALLED_FILE), bytes)
            writeAtomically(File(dir, SEQUENCE_FILE), incoming.sequence.toString().toByteArray())
            incoming
        }
    }

    /**
     * The [registry] with the installed package in [dir] merged in. Installed networks and tokens are only added. The effective
     * whitelist comes from one source only: the installed package's when its sequence is higher than the build's
     * [Registry.catalogSequence], otherwise the build's (a tie keeps the build's). When there is no installed package, or it no
     * longer verifies against [registry] (for example because an app update bakes one of its chain IDs), [registry] is returned
     * unchanged; a failed verification is flagged on the result so Settings can say the built-in catalog is in use.
     */
    fun withInstalled(registry: Registry, dir: File, trustedKeys: List<TrustedKey>): Registry {
        if (!File(dir, INSTALLED_FILE).isFile) return registry
        val installed = installedPackage(registry, dir, trustedKeys) ?: return registry.withInstalledUpdateUnverified()
        val merged = registry.withAdditions(installed.networks, installed.tokens)
        return if (installed.sequence > registry.catalogSequence) {
            merged.withWhitelist(installed.whitelist)
        } else {
            merged
        }
    }

    /**
     * The installed package in [dir], when it verifies against [trustedKeys] and [registry]. It is the package
     * [withInstalled] merges, so its sequence is the one in effect. Null when none is stored or it no longer verifies.
     */
    fun installedPackage(registry: Registry, dir: File, trustedKeys: List<TrustedKey>): RegistryPackage? {
        val file = File(dir, INSTALLED_FILE)
        if (!file.isFile) return null
        return verify(file.readBytes(), trustedKeys, registry).getOrNull()
    }

    /**
     * The highest sequence accepted so far: the larger of the [SEQUENCE_FILE] record and the installed package's own
     * sequence. It is 0 only when nothing was ever installed. An installed package that cannot be read with no record
     * beside it is [RegistryUpdateException.SequenceUnknown], never 0.
     */
    private fun acceptedSequence(dir: File, trustedKeys: List<TrustedKey>): Long {
        val recorded = File(dir, SEQUENCE_FILE).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        val file = File(dir, INSTALLED_FILE)
        if (!file.isFile) return recorded ?: 0
        val bytes = file.readBytes()
        val installed = runCatching { PackageFormat.read(bytes, trustedKeys).sequence }.getOrNull()
            // A package installed before the record existed is format 1, which is no longer verified. It was verified when
            // it was stored, and this directory is private to the app, so its sequence still counts.
            ?: PackageFormat.format1Sequence(bytes)
        if (installed == null && recorded == null) throw RegistryUpdateException.SequenceUnknown()
        return maxOf(installed ?: 0, recorded ?: 0)
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
        for (entry in pkg.whitelist) {
            val chainKnown = baked.network(entry.chainId) != null || pkg.networks.any { it.chainId == entry.chainId }
            if (!chainKnown) {
                throw RegistryUpdateException.InvalidEntry(
                    "whitelist ${entry.signature} is on chain ${entry.chainId}, which is neither baked nor in the package",
                )
            }
        }
    }
}
