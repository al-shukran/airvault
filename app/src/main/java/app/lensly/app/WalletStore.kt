package app.lensly.app

import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.AccountXpub
import app.lensly.core.wallet.BulkPolicy
import app.lensly.core.wallet.BulkRun
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Arrays

/** Public facts about one wallet. None of them can spend funds. */
data class WalletMeta(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val deviceGenerated: Boolean,
    val accountCount: Int,
    /** Master fingerprint as 8 lowercase hex characters. */
    val masterFingerprintHex: String,
    /** Public account data for m/44'/60'/0'. Enough to derive addresses without the secret. */
    val accountXpub: AccountXpub,
)

/** The secret part of a wallet: its 24 words, and its optional BIP39 passphrase (null when there is none). */
class WalletSecret(val words: List<String>, val passphrase: String?)

/**
 * Wallets kept in an open [VaultSession]. Each wallet has two records. Both use DataOutputStream and
 * DataInputStream behind a version byte. Neither is JSON.
 *
 * - `meta`, version 1: name (UTF), created-at ms (long), device-generated (boolean), account count (int),
 *   master fingerprint hex (UTF), account public key and chain code (each an int length, then bytes),
 *   parent fingerprint (int).
 * - `secret`, version 1: word count (byte), each word (UTF), has-passphrase (boolean), then the passphrase
 *   (UTF) when present.
 *
 * Encoded bytes are zeroed after they are written or parsed, including the internal buffer of the stream
 * that produced them. The Strings for the words and the passphrase cannot be zeroed. They are dropped as soon
 * as the wallet has been used.
 *
 * Every call does file or crypto work, so none of them belongs on the main thread.
 */
class WalletStore(private val session: VaultSession) {

    /** Wallets whose metadata reads cleanly, oldest first. */
    fun list(): List<WalletMeta> = session.listWallets()
        .mapNotNull { id -> meta(id) }
        .sortedBy { it.createdAtMs }

    /** The metadata of wallet [id], or null if it does not exist or cannot be read. */
    fun meta(id: String): WalletMeta? {
        val bytes = session.getRecord(id, WalletRecords.META) ?: return null
        return try {
            WalletRecords.decodeMeta(id, bytes)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** The secret of wallet [id], or null if it does not exist. */
    fun secret(id: String): WalletSecret? {
        val bytes = session.getRecord(id, WalletRecords.SECRET) ?: return null
        try {
            return WalletRecords.decodeSecret(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Saves a new wallet with one account and returns its id. The secret is written first and the metadata
     * last, so a wallet is listed only once it is complete. If a write fails, the partial wallet is removed.
     *
     * @throws IllegalArgumentException if [words] is not a valid 24-word mnemonic.
     */
    fun create(
        name: String,
        deviceGenerated: Boolean,
        words: List<String>,
        passphrase: String?,
        nowMs: Long,
    ): String {
        val identity = identityOf(words, passphrase.orEmpty())
        val id = session.createWallet()
        try {
            val secret = WalletRecords.encodeSecret(words, passphrase)
            try {
                session.putRecord(id, WalletRecords.SECRET, secret)
            } finally {
                secret.fill(0)
            }
            val meta = WalletMeta(
                id = id,
                name = name,
                createdAtMs = nowMs,
                deviceGenerated = deviceGenerated,
                accountCount = 1,
                masterFingerprintHex = identity.fingerprintHex,
                accountXpub = identity.xpub,
            )
            session.putRecord(id, WalletRecords.META, WalletRecords.encodeMeta(meta))
        } catch (e: Throwable) {
            session.deleteWallet(id)
            throw e
        }
        return id
    }

    /**
     * Adds the next account of wallet [id] and returns the updated metadata. It runs under the session's lock,
     * so two quick taps cannot claim the same index.
     */
    fun addAccount(id: String): WalletMeta = synchronized(session) {
        val current = requireNotNull(meta(id)) { "No wallet $id." }
        val updated = current.copy(accountCount = current.accountCount + 1)
        session.putRecord(id, WalletRecords.META, WalletRecords.encodeMeta(updated))
        updated
    }

    /**
     * A Bulk Run: adds [count] accounts after the ones wallet [id] already has, and returns the updated metadata.
     *
     * Every new account is derived before anything is saved, so the saved count never covers an account that cannot
     * be derived. The count is then written with the same META record write as [addAccount], under the session's lock.
     * If the account count changed while the run was deriving, nothing is saved and the result is null.
     * [onProgress] receives the number of accounts derived so far. Derivation is slow for large counts, so call this
     * off the main thread.
     *
     * @throws IllegalArgumentException if [count] is outside 1 to [BulkPolicy.DEFAULT_CAP], or the wallet is missing.
     */
    fun addAccounts(id: String, count: Int, onProgress: (Int) -> Unit = {}): WalletMeta? {
        require(BulkPolicy.checkCount(count) is BulkPolicy.CountCheck.Valid) {
            "A Bulk Run makes 1 to ${BulkPolicy.DEFAULT_CAP} accounts."
        }
        val start = requireNotNull(meta(id)) { "No wallet $id." }.accountCount
        val indices = BulkPolicy.indexRange(start, count)
        val secret = requireNotNull(secret(id)) { "No wallet $id." }
        val seed = Mnemonic.toSeed(secret.words, secret.passphrase.orEmpty())
        try {
            // The addresses are not kept. A failure in any of them stops the run here, before anything is saved.
            BulkRun.addresses(HdWallet(seed), indices, onProgress)
        } finally {
            seed.fill(0)
        }
        return synchronized(session) {
            val current = requireNotNull(meta(id)) { "No wallet $id." }
            if (current.accountCount != start) {
                null
            } else {
                val updated = current.copy(accountCount = start + count)
                session.putRecord(id, WalletRecords.META, WalletRecords.encodeMeta(updated))
                updated
            }
        }
    }

    /** Addresses of accounts 1 to [count] of wallet [id], in account order. Derived from the secret. */
    fun addresses(id: String, count: Int): List<String> {
        val secret = requireNotNull(secret(id)) { "No wallet $id." }
        val seed = Mnemonic.toSeed(secret.words, secret.passphrase.orEmpty())
        try {
            val wallet = HdWallet(seed)
            return (0 until count).map { index -> wallet.address(index) }
        } finally {
            seed.fill(0)
        }
    }

    private class Identity(val fingerprintHex: String, val xpub: AccountXpub)

    private fun identityOf(words: List<String>, passphrase: String): Identity {
        val seed = Mnemonic.toSeed(words, passphrase)
        try {
            val wallet = HdWallet(seed)
            return Identity(wallet.masterFingerprintHex, wallet.accountXpub())
        } finally {
            seed.fill(0)
        }
    }
}

/** Binary encodings of the two wallet records. See [WalletStore] for the layout. */
internal object WalletRecords {
    const val META = "meta"
    const val SECRET = "secret"

    private const val VERSION = 1
    private const val MAX_WORDS = 24
    private const val MAX_FIELD_BYTES = 1024

    fun encodeMeta(meta: WalletMeta): ByteArray = writeBytes { out ->
        out.writeByte(VERSION)
        out.writeUTF(meta.name)
        out.writeLong(meta.createdAtMs)
        out.writeBoolean(meta.deviceGenerated)
        out.writeInt(meta.accountCount)
        out.writeUTF(meta.masterFingerprintHex)
        writeField(out, meta.accountXpub.publicKey)
        writeField(out, meta.accountXpub.chainCode)
        out.writeInt(meta.accountXpub.parentFingerprint)
    }

    fun decodeMeta(id: String, bytes: ByteArray): WalletMeta = readBytes(bytes) { input ->
        requireVersion(input)
        val name = input.readUTF()
        val createdAtMs = input.readLong()
        val deviceGenerated = input.readBoolean()
        val accountCount = input.readInt()
        val fingerprintHex = input.readUTF()
        val publicKey = readField(input)
        val chainCode = readField(input)
        val parentFingerprint = input.readInt()
        WalletMeta(
            id = id,
            name = name,
            createdAtMs = createdAtMs,
            deviceGenerated = deviceGenerated,
            accountCount = accountCount,
            masterFingerprintHex = fingerprintHex,
            accountXpub = AccountXpub(publicKey, chainCode, parentFingerprint),
        )
    }

    fun encodeSecret(words: List<String>, passphrase: String?): ByteArray {
        require(words.size in 1..MAX_WORDS) { "A wallet holds 1 to $MAX_WORDS words." }
        return writeBytes { out ->
            out.writeByte(VERSION)
            out.writeByte(words.size)
            words.forEach { out.writeUTF(it) }
            out.writeBoolean(passphrase != null)
            if (passphrase != null) out.writeUTF(passphrase)
        }
    }

    fun decodeSecret(bytes: ByteArray): WalletSecret = readBytes(bytes) { input ->
        requireVersion(input)
        val count = input.readUnsignedByte()
        require(count in 1..MAX_WORDS) { "Word count out of range." }
        val words = List(count) { input.readUTF() }
        val passphrase = if (input.readBoolean()) input.readUTF() else null
        WalletSecret(words, passphrase)
    }

    /** Runs [write] on a fresh stream and returns a copy of its bytes. The stream's own buffer is zeroed too. */
    private inline fun writeBytes(write: (DataOutputStream) -> Unit): ByteArray {
        val buffer = WipingBuffer()
        try {
            DataOutputStream(buffer).use(write)
            return buffer.toByteArray()
        } finally {
            buffer.wipe()
        }
    }

    private inline fun <T> readBytes(bytes: ByteArray, read: (DataInputStream) -> T): T =
        DataInputStream(ByteArrayInputStream(bytes)).use(read)

    private fun writeField(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readField(input: DataInputStream): ByteArray {
        val size = input.readInt()
        require(size in 0..MAX_FIELD_BYTES) { "Field size out of range." }
        return ByteArray(size).also { input.readFully(it) }
    }

    private fun requireVersion(input: DataInputStream) {
        require(input.readUnsignedByte() == VERSION) { "Unknown record version." }
    }

    /** A ByteArrayOutputStream that can zero its buffer, since toByteArray() leaves a copy of the data behind. */
    private class WipingBuffer : ByteArrayOutputStream() {
        fun wipe() {
            Arrays.fill(buf, 0.toByte())
            reset()
        }
    }
}
