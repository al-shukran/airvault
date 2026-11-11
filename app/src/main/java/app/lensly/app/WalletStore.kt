package app.lensly.app

import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.AccountXpub
import app.lensly.core.wallet.BulkPolicy
import app.lensly.core.wallet.BulkRun
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import app.lensly.core.wallet.PrivateKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Arrays

/**
 * What a wallet's secret is. A Mnemonic wallet holds a 24-word Mnemonic and an optional Passphrase, and every account
 * derives from them. A Single Key wallet holds one imported private key. It has exactly one account, and no derivation
 * path.
 */
enum class WalletKind(val wire: Int) {
    MNEMONIC(0),
    SINGLE_KEY(1);

    companion object {
        /** The kind stored as [wire] in a record. An unknown value is refused. */
        fun fromWire(wire: Int): WalletKind = entries.firstOrNull { it.wire == wire }
            ?: throw IllegalArgumentException("Unknown wallet kind.")
    }
}

/**
 * Public facts about one wallet. None of them can spend funds.
 *
 * A Mnemonic wallet has a master fingerprint and an account public key. A Single Key wallet has neither, and both are null.
 */
data class WalletMeta(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val deviceGenerated: Boolean,
    val accountCount: Int,
    /** Master fingerprint as 8 lowercase hex characters. Null for a Single Key, which has no master key. */
    val masterFingerprintHex: String?,
    /** Public account data for m/44'/60'/0'. Enough to derive addresses without the secret. Null for a Single Key. */
    val accountXpub: AccountXpub?,
    /** The kind of secret the wallet holds. A record written before kinds existed is a Mnemonic wallet. */
    val kind: WalletKind = WalletKind.MNEMONIC,
    /**
     * The wallet's name at creation, which its accounts' Auto-Names are built from. [WalletStore.meta] reads it from its own
     * record. A wallet that has none, because it was written before this existed, uses its current name.
     */
    val autoNameBase: String = name,
)

/** The secret part of a wallet, as it is stored. */
sealed interface WalletSecret {
    /**
     * The 24 words, and the BIP39 passphrase (null when there is none). The words and the passphrase are Strings, which
     * cannot be zeroed. They are dropped as soon as the wallet has been used.
     */
    class Mnemonic(val words: List<String>, val passphrase: String?) : WalletSecret

    /** The 32-byte private key. The caller zeroes it once it has been used. */
    class SingleKey(val privateKey: ByteArray) : WalletSecret
}

/**
 * Wallets kept in an open [VaultSession]. Each wallet has a meta and a secret record and a base-name record, and each renamed account has one more. All of them
 * use DataOutputStream and DataInputStream behind a version byte. None is JSON.
 *
 * - `meta`, version 2: kind (byte), name (UTF), created-at ms (long), device-generated (boolean), account count (int).
 *   A Mnemonic wallet then has master fingerprint hex (UTF), account public key and chain code (each an int length,
 *   then bytes), and parent fingerprint (int). A Single Key wallet has nothing more.
 *   Version 1 is the same record without the kind byte. It is always a Mnemonic wallet, and it still loads.
 * - `secret`, version 1 for a Mnemonic wallet: word count (byte), each word (UTF), has-passphrase (boolean), then the
 *   passphrase (UTF) when present. Version 2 for a Single Key wallet: the 32-byte private key.
 * - `account-<index>`, version 1: the Display Name of one renamed account (UTF). Only a renamed account has one, so an
 *   account that keeps its Auto-Name costs no record. Renaming writes it, and a reset removes it.
 * - `base-name`, version 1: the wallet's name at creation (UTF), which its accounts' Auto-Names are built from. It is
 *   written once, at creation, and before the first rename of a wallet that has none. A wallet without one uses its
 *   current name.
 *
 * Encoded bytes are zeroed after they are written or parsed, including the internal buffer of the stream
 * that produced them. The Strings for the words and the passphrase cannot be zeroed. They are dropped as soon
 * as the wallet has been used. The private key of a Single Key secret is the caller's to zero.
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
            val meta = WalletRecords.decodeMeta(id, bytes)
            meta.copy(autoNameBase = storedBaseName(id) ?: meta.name)
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
            session.putRecord(id, WalletRecords.BASE_NAME, WalletRecords.encodeName(name))
            session.putRecord(id, WalletRecords.META, WalletRecords.encodeMeta(meta))
        } catch (e: Throwable) {
            session.deleteWallet(id)
            throw e
        }
        return id
    }

    /**
     * Saves a new Single Key wallet with its one account, and returns its id. [privateKey] is read here and not zeroed, so
     * the caller keeps its copy. The secret is written first and the metadata last, as in [create].
     *
     * @throws IllegalArgumentException if [privateKey] is not a valid key (see [PrivateKey.isValid]).
     */
    fun createSingleKey(name: String, privateKey: ByteArray, nowMs: Long): String {
        require(PrivateKey.isValid(privateKey)) { "Not a valid private key." }
        val id = session.createWallet()
        try {
            val secret = WalletRecords.encodeSingleKeySecret(privateKey)
            try {
                session.putRecord(id, WalletRecords.SECRET, secret)
            } finally {
                secret.fill(0)
            }
            val meta = WalletMeta(
                id = id,
                name = name,
                createdAtMs = nowMs,
                deviceGenerated = false,
                accountCount = 1,
                masterFingerprintHex = null,
                accountXpub = null,
                kind = WalletKind.SINGLE_KEY,
            )
            session.putRecord(id, WalletRecords.BASE_NAME, WalletRecords.encodeName(name))
            session.putRecord(id, WalletRecords.META, WalletRecords.encodeMeta(meta))
        } catch (e: Throwable) {
            session.deleteWallet(id)
            throw e
        }
        return id
    }

    /**
     * Adds the next account of wallet [id] and returns the updated metadata. It runs under the session's lock,
     * so two quick taps cannot claim the same index. A Single Key wallet has one account and refuses this.
     */
    fun addAccount(id: String): WalletMeta = synchronized(session) {
        val current = requireNotNull(meta(id)) { "No wallet $id." }
        require(current.kind == WalletKind.MNEMONIC) { SINGLE_KEY_ONE_ACCOUNT }
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
     * off the main thread. [cap] is the owner's configured Bulk Cap, which the caller reads from [SettingsStore].
     *
     * @throws IllegalArgumentException if [count] is outside 1 to [cap], the wallet is missing, or it is a Single Key wallet.
     */
    fun addAccounts(id: String, count: Int, cap: Int, onProgress: (Int) -> Unit = {}): WalletMeta? {
        require(BulkPolicy.checkCount(count, cap) is BulkPolicy.CountCheck.Valid) {
            "A Bulk Run makes 1 to $cap accounts."
        }
        val before = requireNotNull(meta(id)) { "No wallet $id." }
        require(before.kind == WalletKind.MNEMONIC) { SINGLE_KEY_ONE_ACCOUNT }
        val start = before.accountCount
        val indices = BulkPolicy.indexRange(start, count)
        val secret = mnemonicSecret(id)
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

    /**
     * Addresses of wallet [id], in account order, derived from the secret. A Mnemonic wallet gives the accounts 0 to
     * [count] - 1. A Single Key wallet has one account, so [count] must be 1, and the list holds the key's address.
     */
    fun addresses(id: String, count: Int): List<String> {
        return when (val secret = requireNotNull(secret(id)) { "No wallet $id." }) {
            is WalletSecret.Mnemonic -> {
                val seed = Mnemonic.toSeed(secret.words, secret.passphrase.orEmpty())
                try {
                    val wallet = HdWallet(seed)
                    (0 until count).map { index -> wallet.address(index) }
                } finally {
                    seed.fill(0)
                }
            }
            is WalletSecret.SingleKey -> try {
                require(count == 1) { SINGLE_KEY_ONE_ACCOUNT }
                listOf(AccountAddresses.addressOfPrivateKey(secret.privateKey))
            } finally {
                secret.privateKey.fill(0)
            }
        }
    }

    /**
     * Renames wallet [id] to [name], trimmed, and returns the updated metadata. Only the META record changes, so the
     * secret, the account public key and every account keep what they were. Account names are not rewritten.
     *
     * @throws IllegalArgumentException if [name] is not a valid Display Name, or the wallet does not exist.
     */
    fun renameWallet(id: String, name: String): WalletMeta {
        val clean = displayNameOf(name)
        return synchronized(session) {
            val current = requireNotNull(meta(id)) { "No wallet $id." }
            // A wallet with no base name takes its current name as the base before the rename, so its Auto-Names stay put.
            if (session.getRecord(id, WalletRecords.BASE_NAME) == null) {
                session.putRecord(id, WalletRecords.BASE_NAME, WalletRecords.encodeName(current.autoNameBase))
            }
            val updated = current.copy(name = clean)
            session.putRecord(id, WalletRecords.META, WalletRecords.encodeMeta(updated))
            updated
        }
    }

    /**
     * Crypto-shreds wallet [id]: its Per-Wallet Key goes first, so its records cannot be read even if a file survives. The
     * other wallets are not touched, and an id with no wallet changes nothing.
     *
     * @throws java.io.IOException if the key file cannot be removed. The wallet is then still readable.
     */
    fun delete(id: String) {
        session.deleteWallet(id)
    }

    /** The name the wallet had at creation, from its record, or null if it has no readable record. */
    private fun storedBaseName(id: String): String? {
        val bytes = session.getRecord(id, WalletRecords.BASE_NAME) ?: return null
        return try {
            WalletRecords.decodeName(bytes)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * The Display Name of account [index] of wallet [id], or null when the account keeps its Auto-Name. A record that
     * cannot be read counts as no name, so the account shows its Auto-Name.
     */
    fun accountName(id: String, index: Int): String? {
        val bytes = session.getRecord(id, WalletRecords.accountRecord(index)) ?: return null
        return try {
            WalletRecords.decodeName(bytes)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** The Display Names of the accounts 0 until [count] of wallet [id] that have one, by account index. */
    fun accountNames(id: String, count: Int): Map<Int, String> =
        (0 until count).mapNotNull { index -> accountName(id, index)?.let { index to it } }.toMap()

    /**
     * Gives account [index] of wallet [id] the Display Name [name], trimmed. The account's address and every other account
     * stay as they are.
     *
     * @throws IllegalArgumentException if [name] is not a valid Display Name, or the wallet has no account [index].
     */
    fun renameAccount(id: String, index: Int, name: String) {
        val clean = displayNameOf(name)
        synchronized(session) {
            requireAccount(id, index)
            session.putRecord(id, WalletRecords.accountRecord(index), WalletRecords.encodeName(clean))
        }
    }

    /**
     * Gives account [index] of wallet [id] its default label back. Its profile record is removed, so the account is as it
     * was before it was renamed.
     *
     * @throws IllegalArgumentException if the wallet has no account [index].
     */
    fun resetAccountName(id: String, index: Int) {
        synchronized(session) {
            requireAccount(id, index)
            session.deleteRecord(id, WalletRecords.accountRecord(index))
        }
    }

    /**
     * Batch Rename: gives each account in [indices] of wallet [id] the Display Name "<[base]> <index>", built as an Auto-Name
     * is, in one atomic write. The other accounts keep what they had. [base] is trimmed first.
     *
     * @throws IllegalArgumentException if [indices] is empty, [base] is not a valid batch base for them (see
     * [isValidBatchBase]), or an index is not an account of the wallet. Nothing is saved then.
     */
    fun renameAccounts(id: String, indices: Collection<Int>, base: String) {
        val accounts = selectedAccounts(indices)
        val clean = base.trim()
        require(isValidBatchBase(clean, accounts)) {
            "A batch name is 1 to ${batchBaseMaxLength(accounts)} characters, with no control character."
        }
        synchronized(session) {
            requireAccounts(id, accounts)
            val puts = accounts.associate { index ->
                WalletRecords.accountRecord(index) to WalletRecords.encodeName(autoAccountName(clean, index))
            }
            session.putRecords(id, puts, deletes = emptyList())
        }
    }

    /**
     * Batch reset: gives each account in [indices] of wallet [id] its Auto-Name back. Their records are removed in one atomic
     * write, and the other accounts keep what they had.
     *
     * @throws IllegalArgumentException if [indices] is empty or an index is not an account of the wallet. Nothing is removed then.
     */
    fun resetAccountNames(id: String, indices: Collection<Int>) {
        val accounts = selectedAccounts(indices)
        synchronized(session) {
            requireAccounts(id, accounts)
            session.putRecords(id, puts = emptyMap(), deletes = accounts.map { WalletRecords.accountRecord(it) })
        }
    }

    private fun requireAccount(id: String, index: Int) {
        val current = requireNotNull(meta(id)) { "No wallet $id." }
        require(index in 0 until current.accountCount) { "Wallet $id has no account $index." }
    }

    /** Every account of wallet [id] in [accounts] must exist. */
    private fun requireAccounts(id: String, accounts: List<Int>) {
        val current = requireNotNull(meta(id)) { "No wallet $id." }
        accounts.forEach { index ->
            require(index in 0 until current.accountCount) { "Wallet $id has no account $index." }
        }
    }

    /** The distinct account indices of a selection, in order. An empty selection is refused. */
    private fun selectedAccounts(indices: Collection<Int>): List<Int> {
        require(indices.isNotEmpty()) { "Select at least one account." }
        return indices.toSortedSet().toList()
    }

    /** The Mnemonic secret of wallet [id]. A Single Key secret is zeroed and refused, so the caller never holds a key here. */
    private fun mnemonicSecret(id: String): WalletSecret.Mnemonic =
        when (val secret = requireNotNull(secret(id)) { "No wallet $id." }) {
            is WalletSecret.Mnemonic -> secret
            is WalletSecret.SingleKey -> {
                secret.privateKey.fill(0)
                throw IllegalArgumentException(SINGLE_KEY_ONE_ACCOUNT)
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

    private companion object {
        const val SINGLE_KEY_ONE_ACCOUNT = "A Single Key wallet has exactly one account and cannot generate more."
    }
}

/** Binary encodings of the two wallet records. See [WalletStore] for the layout. */
internal object WalletRecords {
    const val META = "meta"
    const val SECRET = "secret"

    /** The record that holds the wallet's name at creation, which its Auto-Names are built from. */
    const val BASE_NAME = "base-name"

    /** The meta version written before wallet kinds. It has no kind byte, and it is always a Mnemonic wallet. */
    private const val META_VERSION_WITHOUT_KIND = 1
    private const val META_VERSION = 2

    /** The secret version of a Mnemonic wallet. It is the only secret layout before Single Keys. */
    private const val MNEMONIC_SECRET_VERSION = 1
    private const val SINGLE_KEY_SECRET_VERSION = 2
    private const val NAME_VERSION = 1
    private const val MAX_WORDS = 24
    private const val MAX_FIELD_BYTES = 1024

    /** The record that holds the Display Name of account [index]. Only a renamed account has one. */
    fun accountRecord(index: Int): String {
        require(index >= 0) { "An account index is never negative." }
        return "account-$index"
    }

    /** A name record, of an account or of the wallet's base name: the version byte, then the name (UTF). */
    fun encodeName(name: String): ByteArray = writeBytes { out ->
        out.writeByte(NAME_VERSION)
        out.writeUTF(name)
    }

    fun decodeName(bytes: ByteArray): String = readBytes(bytes) { input ->
        when (val version = input.readUnsignedByte()) {
            NAME_VERSION -> displayNameOf(input.readUTF())
            else -> throw IllegalArgumentException("Unknown record version $version.")
        }
    }

    fun encodeMeta(meta: WalletMeta): ByteArray = writeBytes { out ->
        out.writeByte(META_VERSION)
        out.writeByte(meta.kind.wire)
        out.writeUTF(meta.name)
        out.writeLong(meta.createdAtMs)
        out.writeBoolean(meta.deviceGenerated)
        out.writeInt(meta.accountCount)
        when (meta.kind) {
            WalletKind.MNEMONIC -> {
                val xpub = requireNotNull(meta.accountXpub) { "A Mnemonic wallet needs its account public key." }
                out.writeUTF(requireNotNull(meta.masterFingerprintHex) { "A Mnemonic wallet needs its fingerprint." })
                writeField(out, xpub.publicKey)
                writeField(out, xpub.chainCode)
                out.writeInt(xpub.parentFingerprint)
            }
            WalletKind.SINGLE_KEY -> require(
                meta.accountCount == 1 && meta.accountXpub == null && meta.masterFingerprintHex == null,
            ) { "A Single Key wallet has one account and no account public key." }
        }
    }

    fun decodeMeta(id: String, bytes: ByteArray): WalletMeta = readBytes(bytes) { input ->
        val kind = when (val version = input.readUnsignedByte()) {
            META_VERSION_WITHOUT_KIND -> WalletKind.MNEMONIC
            META_VERSION -> WalletKind.fromWire(input.readUnsignedByte())
            else -> throw IllegalArgumentException("Unknown record version $version.")
        }
        val name = input.readUTF()
        val createdAtMs = input.readLong()
        val deviceGenerated = input.readBoolean()
        val accountCount = input.readInt()
        when (kind) {
            WalletKind.MNEMONIC -> {
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
                    kind = kind,
                )
            }
            WalletKind.SINGLE_KEY -> WalletMeta(
                id = id,
                name = name,
                createdAtMs = createdAtMs,
                deviceGenerated = deviceGenerated,
                accountCount = accountCount,
                masterFingerprintHex = null,
                accountXpub = null,
                kind = kind,
            )
        }
    }

    fun encodeSecret(words: List<String>, passphrase: String?): ByteArray {
        require(words.size in 1..MAX_WORDS) { "A wallet holds 1 to $MAX_WORDS words." }
        return writeBytes { out ->
            out.writeByte(MNEMONIC_SECRET_VERSION)
            out.writeByte(words.size)
            words.forEach { out.writeUTF(it) }
            out.writeBoolean(passphrase != null)
            if (passphrase != null) out.writeUTF(passphrase)
        }
    }

    fun encodeSingleKeySecret(privateKey: ByteArray): ByteArray {
        require(privateKey.size == PrivateKey.BYTES) { "A private key is ${PrivateKey.BYTES} bytes." }
        return writeBytes { out ->
            out.writeByte(SINGLE_KEY_SECRET_VERSION)
            out.write(privateKey)
        }
    }

    fun decodeSecret(bytes: ByteArray): WalletSecret = readBytes(bytes) { input ->
        when (val version = input.readUnsignedByte()) {
            MNEMONIC_SECRET_VERSION -> {
                val count = input.readUnsignedByte()
                require(count in 1..MAX_WORDS) { "Word count out of range." }
                val words = List(count) { input.readUTF() }
                val passphrase = if (input.readBoolean()) input.readUTF() else null
                WalletSecret.Mnemonic(words, passphrase)
            }
            SINGLE_KEY_SECRET_VERSION -> {
                val key = ByteArray(PrivateKey.BYTES).also { input.readFully(it) }
                if (!PrivateKey.isValid(key)) {
                    key.fill(0)
                    throw IllegalArgumentException("Stored private key is out of range.")
                }
                WalletSecret.SingleKey(key)
            }
            else -> throw IllegalArgumentException("Unknown record version $version.")
        }
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

    /** A ByteArrayOutputStream that can zero its buffer, since toByteArray() leaves a copy of the data behind. */
    private class WipingBuffer : ByteArrayOutputStream() {
        fun wipe() {
            Arrays.fill(buf, 0.toByte())
            reset()
        }
    }
}
