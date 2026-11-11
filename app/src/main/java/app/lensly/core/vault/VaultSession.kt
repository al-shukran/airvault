package app.lensly.core.vault

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The key file of a wallet folder. It holds the wallet's Per-Wallet Key, wrapped under the vault key that owns the wallet. */
internal const val WALLET_KEY_FILE_NAME = "pwk.bin"

private const val WALLET_KEY_BYTES = 32
private const val BLOB_VERSION: Byte = 1
private val WALLET_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/**
 * File names of vault records. A record's file is named by a keyed hash of its name under the vault key, so the name shows
 * neither the record's name nor its vault. The real vault and the decoy vault keep their records apart in one folder.
 */
internal object VaultRecordNames {
    /**
     * The record names the app writes. Removing a Decoy Vault keeps the files of these names under the real key and deletes
     * the other files. A new vault record name must be added here.
     */
    val KNOWN: List<String> = listOf("settings")

    /** A record name the app can write. */
    val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    private val HASHED_FILE = Regex("[0-9a-f]{64}\\.bin")

    /** The file name of record [name] under [vaultKey]. */
    fun fileName(vaultKey: ByteArray, name: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(vaultKey, "HmacSHA256")) }
        val digest = mac.doFinal(("lensly.vault.record-name\u0000" + name).toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) } + ".bin"
    }

    /**
     * The record name that [fileName] had before names were hashed, or null when the file is not such a file. A file that
     * is already named under the scheme is not one.
     */
    fun legacyNameOf(fileName: String): String? {
        if (!fileName.endsWith(".bin") || HASHED_FILE.matches(fileName)) return null
        return fileName.removeSuffix(".bin").takeIf { NAME.matches(it) }
    }
}

/**
 * True when wallet [walletId] has a key file that [vaultKey] unwraps. A wallet of the other vault, or a damaged one, is not
 * owned, so a session neither lists it nor deletes it.
 */
internal fun ownsWallet(layout: VaultLayout, vaultKey: ByteArray, walletId: String): Boolean {
    val file = File(layout.walletDir(walletId), WALLET_KEY_FILE_NAME)
    if (!file.isFile) return false
    return try {
        // The Per-Wallet Key is only checked here, so it is zeroed at once.
        Aead.open(vaultKey, walletId.toByteArray(StandardCharsets.UTF_8), decodeBlob(file.readBytes())).fill(0)
        true
    } catch (e: AEADBadTagException) {
        false
    } catch (e: VaultException) {
        false
    } catch (e: IOException) {
        false
    }
}

/**
 * An unlocked vault. It holds the Vault Key in memory until [close] is called.
 *
 * Each wallet has a random Per-Wallet Key, wrapped under the Vault Key with AAD = wallet id, in `pwk.bin`. Each record is
 * encrypted under that wallet's key with AAD = wallet id + NUL + record name. [deleteWallet] removes `pwk.bin` first, which
 * shreds the wallet even if removing the record files fails part way.
 *
 * A session is either the real vault or the Decoy Vault ([isDecoy]). Both vaults keep their wallets and records in the same
 * folders, but a session lists, reads and deletes only the files its own key opens. A wallet of the other vault is treated
 * as absent. A decoy session therefore cannot see, change, or delete any real wallet, and the reverse holds too.
 *
 * Record bytes belong to the caller. The session does not zero the array passed to [putRecord], and the array returned by
 * [getRecord] is the caller's to zero.
 *
 * Methods are synchronized on the session, so they can be called from any thread.
 */
class VaultSession internal constructor(
    private val layout: VaultLayout,
    vaultKey: ByteArray,
    /** True for the Decoy Vault, which a Duress Password opens. The App Password never opens it. */
    val isDecoy: Boolean = false,
) {
    private var vaultKey: ByteArray? = vaultKey

    /** Wallet ids this session can open, sorted. Another vault's wallets are not listed. */
    fun listWallets(): List<String> = synchronized(this) {
        val key = requireOpen()
        layout.wallets.list()
            ?.filter { ownsWallet(layout, key, it) }
            ?.sorted()
            ?: emptyList()
    }

    /** Creates a wallet with a fresh Per-Wallet Key and returns its id. */
    fun createWallet(): String = synchronized(this) {
        val key = requireOpen()
        val id = UUID.randomUUID().toString()
        val dir = layout.walletDir(id)
        dir.mkdirs()
        val walletKey = ByteArray(WALLET_KEY_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            val wrapped = Aead.seal(key, id.toByteArray(StandardCharsets.UTF_8), walletKey)
            writeAtomically(walletKeyFile(id), encodeBlob(wrapped))
        } finally {
            Arrays.fill(walletKey, 0)
        }
        id
    }

    /**
     * Stores [bytes] as record [name] of wallet [walletId], replacing any earlier value.
     *
     * @throws IllegalArgumentException if the id is malformed, the name is invalid or reserved, or the wallet does not exist.
     */
    fun putRecord(walletId: String, name: String, bytes: ByteArray): Unit = synchronized(this) {
        val key = requireOpen()
        requireWalletId(walletId)
        requireRecordName(name)
        val walletKey = unwrapWalletKey(key, walletId)
            ?: throw IllegalArgumentException("No wallet with id $walletId.")
        try {
            val sealed = Aead.seal(walletKey, recordAad(walletId, name), bytes)
            writeAtomically(recordFile(walletId, name), encodeBlob(sealed))
        } finally {
            Arrays.fill(walletKey, 0)
        }
    }

    /** The record's bytes, or null if the wallet or the record does not exist (including after [deleteWallet]). */
    fun getRecord(walletId: String, name: String): ByteArray? {
        synchronized(this) {
            val key = requireOpen()
            requireWalletId(walletId)
            requireRecordName(name)
            val file = recordFile(walletId, name)
            if (!file.isFile) return null
            val walletKey = unwrapWalletKey(key, walletId) ?: return null
            try {
                return Aead.open(walletKey, recordAad(walletId, name), decodeBlob(file.readBytes()))
            } catch (e: AEADBadTagException) {
                throw VaultException("Record '$name' of wallet $walletId failed authentication.", e)
            } finally {
                Arrays.fill(walletKey, 0)
            }
        }
    }

    /**
     * The names of the records of wallet [walletId] that start with [prefix], sorted. A wallet this session cannot open has
     * none, so neither vault lists the other's records.
     *
     * @throws IllegalArgumentException if the id is malformed.
     */
    fun recordNames(walletId: String, prefix: String): List<String> {
        synchronized(this) {
            val key = requireOpen()
            requireWalletId(walletId)
            if (!ownsWallet(layout, key, walletId)) return emptyList()
            return layout.walletDir(walletId).list().orEmpty()
                .filter { it.startsWith(prefix) && it.endsWith(".bin") }
                .map { it.removeSuffix(".bin") }
                .sorted()
        }
    }

    /** Removes record [name] of wallet [walletId]. Does nothing if the record does not exist. */
    fun deleteRecord(walletId: String, name: String): Unit = synchronized(this) {
        requireOpen()
        requireWalletId(walletId)
        requireRecordName(name)
        Files.deleteIfExists(recordFile(walletId, name).toPath())
    }

    /**
     * Applies several record changes to wallet [walletId] as one write: each record in [puts] is stored, and each name in
     * [deletes] is removed. The session lock is held throughout, and nothing is written unless every name is valid and
     * every record seals. If a file cannot be written part way, the files already changed are put back, so either all of
     * the changes land or none does. A crash in the middle of the writes can still leave some of them, since a
     * filesystem commits one file at a time.
     *
     * @throws IllegalArgumentException if the id or a name is malformed or reserved, a name is both put and deleted, or the
     * wallet does not exist.
     */
    fun putRecords(walletId: String, puts: Map<String, ByteArray>, deletes: Collection<String>): Unit = synchronized(this) {
        val key = requireOpen()
        requireWalletId(walletId)
        (puts.keys + deletes).forEach { requireRecordName(it) }
        require(puts.keys.none { it in deletes }) { "A record cannot be stored and removed in one write." }
        val walletKey = unwrapWalletKey(key, walletId)
            ?: throw IllegalArgumentException("No wallet with id $walletId.")
        // Every record is sealed before any file changes, so a failure here writes nothing.
        val sealed = try {
            puts.map { (name, bytes) ->
                recordFile(walletId, name) to encodeBlob(Aead.seal(walletKey, recordAad(walletId, name), bytes))
            }
        } finally {
            Arrays.fill(walletKey, 0)
        }
        val targets = (sealed.map { it.first } + deletes.map { recordFile(walletId, it) }).distinct()
        // What each target holds now, so a failed write can put it back. Null means the record does not exist.
        val before = targets.associateWith { file -> if (file.isFile) file.readBytes() else null }
        try {
            sealed.forEach { (file, blob) -> writeAtomically(file, blob) }
            deletes.forEach { name -> Files.deleteIfExists(recordFile(walletId, name).toPath()) }
        } catch (e: Throwable) {
            restore(before, e)
            throw e
        }
    }

    /** Puts back what [before] recorded for each file. A file that could not be put back is added to [failure]. */
    private fun restore(before: Map<File, ByteArray?>, failure: Throwable) {
        before.forEach { (file, bytes) ->
            try {
                if (bytes == null) Files.deleteIfExists(file.toPath()) else writeAtomically(file, bytes)
            } catch (e: Throwable) {
                failure.addSuppressed(e)
            }
        }
    }

    /**
     * Crypto-shreds a wallet: removes its wrapped Per-Wallet Key first, then its record files. Does nothing if the wallet does
     * not exist, or if this session cannot open it. A wallet of the other vault is never deleted from here.
     */
    fun deleteWallet(walletId: String) {
        synchronized(this) {
            val key = requireOpen()
            requireWalletId(walletId)
            val dir = layout.walletDir(walletId)
            if (!dir.isDirectory) return
            if (!ownsWallet(layout, key, walletId)) return
            Files.deleteIfExists(walletKeyFile(walletId).toPath())
            dir.deleteRecursively()
        }
    }

    /**
     * Stores [bytes] as the vault record [name], replacing any earlier value. A vault record belongs to the vault, not to a
     * wallet, so it holds no wallet data: a setting, for example. It is encrypted under the Vault Key itself, with its own
     * AAD, and its file name is keyed by the Vault Key (see [VaultRecordNames]). [Vault.rekey] keeps the Vault Key, so the
     * record stays readable after a password change, and [Vault.wipe] removes it.
     *
     * @throws IllegalArgumentException if the name is invalid.
     */
    fun putVaultRecord(name: String, bytes: ByteArray): Unit = synchronized(this) {
        val key = requireOpen()
        requireRecordName(name)
        layout.vaultRecords.mkdirs()
        writeAtomically(vaultRecordFile(key, name), encodeBlob(Aead.seal(key, vaultRecordAad(name), bytes)))
    }

    /** The vault record [name], or null if it has never been stored. */
    fun getVaultRecord(name: String): ByteArray? = synchronized(this) {
        val key = requireOpen()
        requireRecordName(name)
        val file = vaultRecordFile(key, name)
        if (!file.isFile) return@synchronized null
        try {
            Aead.open(key, vaultRecordAad(name), decodeBlob(file.readBytes()))
        } catch (e: AEADBadTagException) {
            throw VaultException("Vault record '$name' failed authentication.", e)
        }
    }

    /**
     * Renames vault records written under plain names, which the app used before names were keyed. A file keeps its contents:
     * the record name is bound into its AAD, not into its file name. A file that cannot be moved stays where it is, and the
     * next unlock tries again.
     */
    internal fun migrateLegacyRecordNames(): Unit = synchronized(this) {
        val key = requireOpen()
        layout.vaultRecords.listFiles()?.forEach { file ->
            val name = VaultRecordNames.legacyNameOf(file.name) ?: return@forEach
            val target = vaultRecordFile(key, name)
            if (target.exists()) return@forEach
            try {
                Files.move(file.toPath(), target.toPath())
            } catch (e: IOException) {
                // Left in place. The next unlock tries again.
            }
        }
    }

    /** Zeroes the Vault Key. Any later call on this session throws [IllegalStateException]. */
    fun close() {
        synchronized(this) {
            vaultKey?.let { Arrays.fill(it, 0) }
            vaultKey = null
        }
    }

    /** A copy of the Vault Key, for [Vault.rekey] and the Duress Password operations. The caller zeroes it. */
    internal fun copyOfVaultKey(): ByteArray = synchronized(this) { requireOpen().copyOf() }

    /** The file that holds vault record [name] for this session. For tests only. */
    internal fun vaultRecordFileForTesting(name: String): File = synchronized(this) {
        vaultRecordFile(requireOpen(), name)
    }

    private fun requireOpen(): ByteArray = vaultKey ?: throw IllegalStateException("The VaultSession is closed.")

    private fun walletKeyFile(walletId: String): File = File(layout.walletDir(walletId), WALLET_KEY_FILE_NAME)

    private fun recordFile(walletId: String, name: String): File =
        File(layout.walletDir(walletId), "$name.bin")

    private fun vaultRecordFile(vaultKey: ByteArray, name: String): File =
        File(layout.vaultRecords, VaultRecordNames.fileName(vaultKey, name))

    /** Returns the Per-Wallet Key, or null if the wallet has no key file. The caller zeroes the result. */
    private fun unwrapWalletKey(vaultKey: ByteArray, walletId: String): ByteArray? {
        val file = walletKeyFile(walletId)
        if (!file.isFile) return null
        return try {
            Aead.open(vaultKey, walletId.toByteArray(StandardCharsets.UTF_8), decodeBlob(file.readBytes()))
        } catch (e: AEADBadTagException) {
            throw VaultException("The key of wallet $walletId failed authentication.", e)
        }
    }

    private companion object {
        fun requireWalletId(id: String) {
            require(WALLET_ID.matches(id)) { "Malformed wallet id." }
        }

        fun requireRecordName(name: String) {
            // "pwk" is reserved: a record of that name would overwrite the wallet's key file.
            require(VaultRecordNames.NAME.matches(name) && name != "pwk") { "Invalid record name." }
        }

        fun recordAad(walletId: String, name: String): ByteArray =
            (walletId + "\u0000" + name).toByteArray(StandardCharsets.UTF_8)

        /** A UUID never starts with "vault-record", so these AADs cannot collide with a wallet record's. */
        fun vaultRecordAad(name: String): ByteArray =
            ("vault-record\u0000" + name).toByteArray(StandardCharsets.UTF_8)
    }
}

private fun encodeBlob(sealed: ByteArray): ByteArray = byteArrayOf(BLOB_VERSION) + sealed

private fun decodeBlob(bytes: ByteArray): ByteArray {
    if (bytes.size < 1 + Aead.IV_BYTES + 16 || bytes[0] != BLOB_VERSION) {
        throw VaultException("A wallet file is damaged or not in a format this app reads.")
    }
    return bytes.copyOfRange(1, bytes.size)
}
