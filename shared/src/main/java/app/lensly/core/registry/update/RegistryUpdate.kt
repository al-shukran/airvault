package app.lensly.core.registry.update

import app.lensly.core.registry.NetworkKind
import app.lensly.core.registry.Registry
import app.lensly.core.registry.WhitelistEntry
import app.lensly.core.vault.KeystoreBackend
import app.lensly.core.vault.writeAtomically
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Offline catalog updates. A registry package adds networks and tokens to the baked-in catalog. It can never
 * change or remove a baked entry. Its whitelist, denylist and wallets are the effective ones only when the package is newer by
 * sequence than the build's catalog; otherwise the build's lists stay. A list the package does not carry keeps the one in effect:
 * the whitelist keeps the last installed one that carried it, and the denylist and wallets keep the build's.
 * A package is accepted only when a key the build trusts signed it (see [TrustedKeys]), and only when its sequence is higher than
 * every sequence accepted before. The highest accepted sequence, and the sequence of the package that supplied the kept whitelist, are
 * sealed under a Keystore key (see [RegistrySeal]), so replacing or deleting the stored files cannot roll the catalog back: a package
 * or whitelist that does not match the seal is not used, and an install must beat the floor the Keystore remembers.
 *
 * Packages arrive on removable storage. This object checks them before anything is stored. Reading them from
 * storage is the caller's job.
 */
object RegistryUpdate {
    /** The accepted package, stored in the registry directory that the caller passes to [install]. */
    const val INSTALLED_FILE = "installed.lrp"

    /**
     * The highest sequence accepted, as decimal text, that Lensly 1.2.0 stored beside [INSTALLED_FILE]. It is no longer written. A store
     * that has it and no seal is sealed once by [adoptUnsealed], and the file is deleted.
     */
    internal const val LEGACY_SEQUENCE_FILE = "accepted-sequence"

    /**
     * The last accepted package that carried a whitelist, stored beside [INSTALLED_FILE]. A later package that omits its whitelist
     * keeps this one's, so the installed whitelist survives an update that does not restate it. Its sequence is the one in the seal.
     */
    const val WHITELIST_FILE = "whitelist.lrp"

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
     * accepted one, which is the highest of the sealed floor, the Keystore's newest floor key and the installed package's own sequence.
     * An older sequence is refused with [RegistryUpdateException.NotNewer], and so is an equal one while the seal verifies. When no seal
     * verifies, an equal sequence is accepted and sealed again: a crash between making the Keystore key and writing the seal, or a lost
     * key, would otherwise leave the store unverified until a higher package arrives. A directory that exists but holds a package
     * that cannot be read, and no floor anywhere, is [RegistryUpdateException.SequenceUnknown], not "nothing accepted yet": deleting
     * the files must not lower the floor. The files and the seal are written one by one. When a write fails, the files already written
     * are put back as they were and the new Keystore key is deleted, so a failure leaves nothing stored and the result says so.
     * Accepting an equal sequence while no seal verifies assumes the publisher never issues two different packages with one
     * sequence. Such a same-sequence recovery resets the whitelist carrier, so a kept whitelist from an earlier package is dropped
     * until a package carrying a whitelist is installed.
     */
    fun install(
        bytes: ByteArray,
        trustedKeys: List<TrustedKey>,
        baked: Registry,
        dir: File,
        keystore: KeystoreBackend,
    ): Result<RegistryPackage> = synchronized(installLock) {
        runCatching {
            val incoming = verify(bytes, trustedKeys, baked).getOrThrow()
            adoptUnsealed(dir, trustedKeys, keystore)
            val prior = sealOf(dir, keystore)
            val newest = RegistrySeal.newestFloor(keystore)
            val accepted = acceptedSequence(dir, trustedKeys, prior, newest)
            // With no verified seal the same sequence is accepted once more, to seal again after a crash or a lost key.
            if (incoming.sequence < accepted || (incoming.sequence == accepted && prior != null)) {
                throw RegistryUpdateException.NotNewer(accepted, incoming.sequence)
            }
            val created = !dir.isDirectory
            check(!created || dir.mkdirs()) { "cannot create ${dir.path}" }
            // A package that omits its whitelist keeps the sealed carrier's. With no verified seal there is no carrier to keep.
            val carrier = if (incoming.whitelist != null) incoming.sequence else prior?.whitelistSequence ?: 0L
            val seal = RegistrySeal.Seal(incoming.sequence, carrier)
            try {
                // The package goes first and the seal last: a seal is the proof that the files before it were all written.
                val writes = linkedMapOf(INSTALLED_FILE to bytes)
                if (incoming.whitelist != null) writes[WHITELIST_FILE] = bytes
                writes[RegistrySeal.FILE] = RegistrySeal.create(seal, keystore)
                storeAll(dir, writes, created)
            } catch (e: Exception) {
                RegistrySeal.keepOnly(newest, keystore)
                throw e
            }
            RegistrySeal.keepOnly(seal.floor, keystore)
            incoming
        }
    }

    /**
     * Seals a store that has no seal and whose Keystore holds no registry key: one that Lensly 1.2.0 wrote, which kept the floor in
     * [LEGACY_SEQUENCE_FILE] where a file edit could change it. The floor becomes the highest sequence among the installed package, the
     * whitelist carrier and that record, and the carrier's sequence is sealed with it. This runs once: the key it makes means the next
     * read finds a seal. A failure leaves the store as it was, to be tried again.
     *
     * Adopting is safe because this state cannot be reached by file access alone: a store that was ever sealed has a Keystore key,
     * which an attacker without the Keystore cannot delete, so a deleted or copied seal reads as unverified, never as unsealed. What
     * gets adopted is what the store held when the app first ran with seals, which is all that 1.2.0 ever protected. It is not
     * trusted beyond what verifies: the packages must still carry a trusted signature, and only their sequences are taken.
     */
    fun adoptUnsealed(dir: File, trustedKeys: List<TrustedKey>, keystore: KeystoreBackend) {
        synchronized(installLock) {
            if (RegistrySeal.read(dir, keystore) != RegistrySeal.Reading.Unsealed) return
            val record = File(dir, LEGACY_SEQUENCE_FILE)
            val installed = sequenceOf(File(dir, INSTALLED_FILE), trustedKeys)
            val carrier = sequenceOf(File(dir, WHITELIST_FILE), trustedKeys)
            val floor = maxOf(installed ?: 0L, carrier ?: 0L, recordedSequence(record) ?: 0L)
            if (floor == 0L) return
            try {
                writeAtomically(File(dir, RegistrySeal.FILE), RegistrySeal.create(RegistrySeal.Seal(floor, carrier ?: 0L), keystore))
            } catch (e: Exception) {
                RegistrySeal.keepOnly(null, keystore)
                throw e
            }
            record.delete()
        }
    }

    /** Writes [writes] in order. When one fails, the ones already written go back to what they held and the failure is rethrown. */
    private fun storeAll(dir: File, writes: Map<String, ByteArray>, created: Boolean) {
        val before = writes.keys.associateWith { name -> File(dir, name).takeIf { it.isFile }?.readBytes() }
        val written = mutableListOf<String>()
        try {
            for ((name, content) in writes) {
                writeAtomically(File(dir, name), content)
                written += name
            }
        } catch (e: Exception) {
            for (name in written) {
                val file = File(dir, name)
                runCatching { before[name]?.let { writeAtomically(file, it) } ?: file.delete() }
            }
            if (created) dir.delete()
            throw e
        }
    }

    /**
     * The [registry] with the installed package in [dir] merged in. Installed networks and tokens are only added. The effective
     * whitelist, denylist and wallets come from one source only: the installed package's when its sequence is higher than the
     * build's [Registry.catalogSequence], otherwise the build's (a tie keeps the build's). A list the package does not carry keeps
     * the one in effect: the whitelist keeps the last installed one that carried it, see [keptWhitelist]. The denylist and wallets
     * keep the build's. When there is no installed package, or it no longer verifies against [registry] (for
     * example because an app update bakes one of its chain IDs), [registry] is returned unchanged; a failed verification is
     * flagged on the result so Settings can say the built-in catalog is in use. A package that does not match the seal counts as
     * one that no longer verifies, and so does any store whose seal cannot be verified (see [RegistrySeal]).
     */
    fun withInstalled(registry: Registry, dir: File, trustedKeys: List<TrustedKey>, keystore: KeystoreBackend): Registry {
        if (!File(dir, INSTALLED_FILE).isFile) return registry
        val seal = sealOf(dir, keystore) ?: return registry.withInstalledUpdateUnverified()
        val installed = installedUnder(seal, registry, dir, trustedKeys) ?: return registry.withInstalledUpdateUnverified()
        val merged = registry.withAdditions(installed.networks, installed.tokens)
        if (installed.sequence <= registry.catalogSequence) return merged
        return merged
            .withWhitelist(installed.whitelist ?: keptWhitelist(seal, registry, dir, trustedKeys) ?: registry.whitelistEntries())
            .withDenylist(installed.denylist ?: registry.denylistEntries())
            .withWallets(installed.wallets ?: registry.walletEntries())
    }

    /**
     * The whitelist of the last installed package that carried one, when it is the package the seal names, verifies against
     * [registry] and is newer than its catalog, as the whitelist rule requires. A file with any other sequence is a replaced one.
     * Null when there is none in effect.
     */
    private fun keptWhitelist(
        seal: RegistrySeal.Seal,
        registry: Registry,
        dir: File,
        trustedKeys: List<TrustedKey>,
    ): List<WhitelistEntry>? {
        val file = File(dir, WHITELIST_FILE)
        if (seal.whitelistSequence == 0L || !file.isFile) return null
        val kept = verify(file.readBytes(), trustedKeys, registry).getOrNull() ?: return null
        if (kept.sequence != seal.whitelistSequence || kept.sequence <= registry.catalogSequence) return null
        return kept.whitelist
    }

    /**
     * The installed package in [dir], when it verifies against [trustedKeys] and [registry]. It is the package
     * [withInstalled] merges, so its sequence is the one in effect. Null when none is stored, it no longer verifies, the seal cannot be
     * verified, or its sequence is below the sealed floor (a replaced file).
     */
    fun installedPackage(registry: Registry, dir: File, trustedKeys: List<TrustedKey>, keystore: KeystoreBackend): RegistryPackage? =
        sealOf(dir, keystore)?.let { installedUnder(it, registry, dir, trustedKeys) }

    private fun installedUnder(seal: RegistrySeal.Seal, registry: Registry, dir: File, trustedKeys: List<TrustedKey>): RegistryPackage? {
        val file = File(dir, INSTALLED_FILE)
        if (!file.isFile) return null
        val installed = verify(file.readBytes(), trustedKeys, registry).getOrNull() ?: return null
        return installed.takeIf { it.sequence >= seal.floor }
    }

    private fun sealOf(dir: File, keystore: KeystoreBackend): RegistrySeal.Seal? =
        (RegistrySeal.read(dir, keystore) as? RegistrySeal.Reading.Sealed)?.seal

    private fun recordedSequence(record: File): Long? = record.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()

    /** The sequence of the package in [file] when it can be read and a trusted key signed it, otherwise null. */
    private fun sequenceOf(file: File, trustedKeys: List<TrustedKey>): Long? =
        file.takeIf { it.isFile }?.let { runCatching { PackageFormat.read(it.readBytes(), trustedKeys).sequence }.getOrNull() }

    /**
     * The highest sequence accepted so far: the largest of the [prior] seal's floor, the Keystore's [newest] floor key and the
     * installed package's own sequence. The Keystore's key outlives the files, so deleting them leaves the floor in place. It is 0
     * only when none of them exists and [dir] does not exist either. An existing [dir] with none of them is
     * [RegistryUpdateException.SequenceUnknown], never 0.
     */
    private fun acceptedSequence(dir: File, trustedKeys: List<TrustedKey>, prior: RegistrySeal.Seal?, newest: Long?): Long {
        val known = listOfNotNull(sequenceOf(File(dir, INSTALLED_FILE), trustedKeys), prior?.floor, newest)
        if (known.isEmpty() && dir.isDirectory) throw RegistryUpdateException.SequenceUnknown()
        return known.maxOrNull() ?: 0L
    }

    private fun checkAgainstBaked(pkg: RegistryPackage, baked: Registry) {
        for (network in pkg.networks) {
            if (baked.network(network.chainId) != null) {
                throw RegistryUpdateException.Conflict("chain ${network.chainId} is already in the baked catalog")
            }
            val parent = network.parentChainId ?: continue
            val settlesTo = baked.network(parent) ?: pkg.networks.firstOrNull { it.chainId == parent }
                ?: throw RegistryUpdateException.InvalidEntry(
                    "chain ${network.chainId} settles to chain $parent, which is neither baked nor in the package",
                )
            if (settlesTo.kind != NetworkKind.LAYER_1) {
                throw RegistryUpdateException.InvalidEntry("chain ${network.chainId} settles to chain $parent, which is not a Layer 1")
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
        for (entry in pkg.whitelist.orEmpty()) {
            val chainKnown = baked.network(entry.chainId) != null || pkg.networks.any { it.chainId == entry.chainId }
            if (!chainKnown) {
                throw RegistryUpdateException.InvalidEntry(
                    "whitelist ${entry.signature} is on chain ${entry.chainId}, which is neither baked nor in the package",
                )
            }
        }
    }
}
