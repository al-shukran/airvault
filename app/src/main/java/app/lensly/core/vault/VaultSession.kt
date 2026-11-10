package app.lensly.core.vault

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID
import javax.crypto.AEADBadTagException

/**
 * An unlocked vault. It holds the Vault Key in memory until [close] is called.
 *
 * Each wallet has a random Per-Wallet Key, wrapped under the Vault Key with AAD = wallet id, in `pwk.bin`.
 * Each record is encrypted under that wallet's key with AAD = wallet id + NUL + record name. [deleteWallet] removes
 * `pwk.bin` first, which shreds the wallet even if removing the record files fails part way.
 *
 * Record bytes belong to the caller. The session does not zero the array passed to [putRecord], and the array
 * returned by [getRecord] is the caller's to zero.
 *
 * Methods are synchronized on the session, so they can be called from any thread.
 */
class VaultSession internal constructor(
    private val layout: VaultLayout,
    vaultKey: ByteArray,
) {
    private var vaultKey: ByteArray? = vaultKey

    /** Wallet ids, sorted. A directory without `pwk.bin` is not a wallet. */
    fun listWallets(): List<String> = synchronized(this) {
        requireOpen()
        layout.wallets.list()
            ?.filter { walletKeyFile(it).isFile }
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
     * Crypto-shreds a wallet: removes its wrapped Per-Wallet Key first, then its record files.
     * Does nothing if the wallet does not exist.
     */
    fun deleteWallet(walletId: String) {
        synchronized(this) {
            requireOpen()
            requireWalletId(walletId)
            val dir = layout.walletDir(walletId)
            if (!dir.isDirectory) return
            Files.deleteIfExists(walletKeyFile(walletId).toPath())
            dir.deleteRecursively()
        }
    }

    /**
     * Stores [bytes] as the vault record [name], replacing any earlier value. A vault record belongs to the vault,
     * not to a wallet, so it holds no wallet data: a setting, for example. It is encrypted under the Vault Key itself,
     * with its own AAD. [Vault.rekey] keeps the Vault Key, so the record stays readable after a password change,
     * and [Vault.wipe] removes it.
     *
     * @throws IllegalArgumentException if the name is invalid.
     */
    fun putVaultRecord(name: String, bytes: ByteArray): Unit = synchronized(this) {
        val key = requireOpen()
        requireRecordName(name)
        layout.vaultRecords.mkdirs()
        writeAtomically(vaultRecordFile(name), encodeBlob(Aead.seal(key, vaultRecordAad(name), bytes)))
    }

    /** The vault record [name], or null if it has never been stored. */
    fun getVaultRecord(name: String): ByteArray? = synchronized(this) {
        val key = requireOpen()
        requireRecordName(name)
        val file = vaultRecordFile(name)
        if (!file.isFile) return@synchronized null
        try {
            Aead.open(key, vaultRecordAad(name), decodeBlob(file.readBytes()))
        } catch (e: AEADBadTagException) {
            throw VaultException("Vault record '$name' failed authentication.", e)
        }
    }

    /** Zeroes the Vault Key. Any later call on this session throws [IllegalStateException]. */
    fun close() {
        synchronized(this) {
            vaultKey?.let { Arrays.fill(it, 0) }
            vaultKey = null
        }
    }

    /** A copy of the Vault Key, for [Vault.rekey]. The caller zeroes it. */
    internal fun copyOfVaultKey(): ByteArray = synchronized(this) { requireOpen().copyOf() }

    private fun requireOpen(): ByteArray = vaultKey ?: throw IllegalStateException("The VaultSession is closed.")

    private fun walletKeyFile(walletId: String): File = File(layout.walletDir(walletId), WALLET_KEY_FILE)

    private fun recordFile(walletId: String, name: String): File =
        File(layout.walletDir(walletId), "$name.bin")

    private fun vaultRecordFile(name: String): File = File(layout.vaultRecords, "$name.bin")

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

    private fun encodeBlob(sealed: ByteArray): ByteArray = byteArrayOf(BLOB_VERSION) + sealed

    private fun decodeBlob(bytes: ByteArray): ByteArray {
        if (bytes.size < 1 + Aead.IV_BYTES + 16 || bytes[0] != BLOB_VERSION) {
            throw VaultException("A wallet file is damaged or not in a format this app reads.")
        }
        return bytes.copyOfRange(1, bytes.size)
    }

    private companion object {
        const val WALLET_KEY_FILE = "pwk.bin"
        const val WALLET_KEY_BYTES = 32
        const val BLOB_VERSION: Byte = 1
        val WALLET_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val RECORD_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

        fun requireWalletId(id: String) {
            require(WALLET_ID.matches(id)) { "Malformed wallet id." }
        }

        fun requireRecordName(name: String) {
            // "pwk" is reserved: a record of that name would overwrite the wallet's key file.
            require(RECORD_NAME.matches(name) && name != "pwk") { "Invalid record name." }
        }

        fun recordAad(walletId: String, name: String): ByteArray =
            (walletId + "\u0000" + name).toByteArray(StandardCharsets.UTF_8)

        /** A UUID never starts with "vault-record", so these AADs cannot collide with a wallet record's. */
        fun vaultRecordAad(name: String): ByteArray =
            ("vault-record\u0000" + name).toByteArray(StandardCharsets.UTF_8)
    }
}
