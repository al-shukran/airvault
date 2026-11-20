package app.lensly.app

import app.airvault.chains.evm.signer.EvmSigner
import app.airvault.core.signer.KeyAccess
import app.lensly.app.export.BrotliCompressor
import app.lensly.app.export.BrotliEncoder
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingPayload
import app.lensly.app.export.fitBytes
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcAccountSelection
import app.airvault.chains.bitcoin.signer.BtcFeeThresholds
import app.lensly.core.btc.BtcNetwork
import app.airvault.chains.bitcoin.signer.BtcRefusal
import app.airvault.chains.bitcoin.signer.BtcRefusedException
import app.lensly.core.btc.BtcSigner
import app.airvault.chains.bitcoin.signer.InputVerificationPolicy
import app.lensly.core.btc.ScriptType
import app.airvault.chains.bitcoin.signer.SigningSourcePolicy
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.vault.VaultSession
import app.lensly.core.vault.WalletRecordNames
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.AccountXpub
import app.lensly.core.wallet.SingleKeyAccess
import app.lensly.core.wallet.WalletKeyAccess
import app.lensly.core.wallet.BulkPolicy
import app.lensly.core.wallet.BulkRun
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import app.lensly.core.wallet.PrivateKey
import fr.acinq.bitcoin.psbt.Psbt
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Arrays

/** The mask of an unsigned 32-bit value, which a master fingerprint is when it is read as a Long. */
private const val UINT32_MASK = 0xFFFFFFFFL

/** The name a pairing carries for a wallet that has none. */
private const val DEFAULT_PAIRING_NAME = "Lensly"

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
    /**
     * The public account key of each account, m/44'/60'/N' for account N, in account order: one for each of the [accountCount] accounts. It is
     * enough to derive every address without the secret. Empty for a Single Key.
     */
    val accountKeys: List<AccountXpub>,
    /** The kind of secret the wallet holds. */
    val kind: WalletKind = WalletKind.MNEMONIC,
    /**
     * The wallet's name at creation, which its accounts' Auto-Names are built from. [WalletStore.meta] reads it from its own record.
     */
    val autoNameBase: String = name,
    /**
     * True when the owner has backed up the seed phrase. The create ceremony sets it once its quiz passes, a restore sets it, and so does a
     * Single Key import. A wallet with it false is asked for a backup on Home.
     */
    val seedBackedUp: Boolean = true,
)

/**
 * The public view of a wallet's Ethereum accounts: its metadata, the profiles of the accounts that have one, by index, and the address
 * of each account, in order. It holds no secret, so the session may keep it until the vault locks.
 */
class EthereumAccounts(val meta: WalletMeta, val profiles: Map<Int, AccountProfile>, val addresses: List<String>)

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
 * Wallets kept in an open [VaultSession]. Each wallet has a meta and a secret record and a base-name record. An account with
 * a profile has one more, and so does a wallet with a profile. All of them use DataOutputStream and DataInputStream behind
 * a version byte. None is JSON.
 *
 * - `meta`, version 4: kind (byte), name (UTF), created-at ms (long), device-generated (boolean), account count (int), seed backed up
 *   (boolean). A Mnemonic wallet then has master fingerprint hex (UTF), account public key and chain code (each an int length,
 *   then bytes), and parent fingerprint (int). A Single Key wallet has nothing more.
 * - `secret`, version 1 for a Mnemonic wallet: word count (byte), each word (UTF), has-passphrase (boolean), then the
 *   passphrase (UTF) when present. Version 2 for a Single Key wallet: the 32-byte private key.
 * - `account-<index>`, version 2: a flags byte, then each field the flags name, in order: the Display Name (UTF), the colour
 *   and the icon (one byte each, a code from [ProfileColour] or [ProfileIcon]), the label (UTF) and the group (UTF). An account
 *   whose fields are all defaults has no record, so an account that keeps its Auto-Name and its generated look costs nothing.
 * - `profile`, version 1, for a wallet: a flags byte for the colour, icon and label, then those fields that are set, then the
 *   group count (byte) and each group (UTF). A wallet whose fields are all defaults has no record.
 * - `base-name`, version 1: the wallet's name at creation (UTF), which its accounts' Auto-Names are built from. It is
 *   written once, at creation. A wallet without one is not listed.
 * - `bitcoin`, version 2: the count (int), then each Bitcoin account: its network as its BIP44 coin type (byte), its Script Type as
 *   its BIP43 purpose (byte), its index (int), its first receive address (UTF), and a byte that is 1 when it has a profile, which then
 *   follows in the layout of an account record. A wallet with no
 *   record has no Bitcoin account. The record is one fixed size, so the profiles share the room the accounts leave.
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
            meta.copy(autoNameBase = storedBaseName(id) ?: return null)
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
        seedBackedUp: Boolean = true,
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
                accountKeys = identity.keys,
                seedBackedUp = seedBackedUp,
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
                accountKeys = emptyList(),
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
        val key = accountKeyOf(id, current.accountCount)
        val updated = current.copy(accountCount = current.accountCount + 1, accountKeys = current.accountKeys + key)
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
        val seed = Mnemonic.of(secret.words).use { it.deriveSeed(secret.passphrase.orEmpty()) }
        val keys = try {
            // A failure in any of them stops the run here, before anything is saved.
            BulkRun.accountKeys(HdWallet(seed), indices, onProgress)
        } finally {
            seed.fill(0)
        }
        return synchronized(session) {
            val current = requireNotNull(meta(id)) { "No wallet $id." }
            if (current.accountCount != start) {
                null
            } else {
                val updated = current.copy(accountCount = start + count, accountKeys = current.accountKeys + keys)
                session.putRecord(id, WalletRecords.META, WalletRecords.encodeMeta(updated))
                updated
            }
        }
    }

    /**
     * The Bitcoin accounts of wallet [id], in the order they were added. A wallet with none, or with a record that cannot be read,
     * has an empty list.
     */
    fun bitcoinAccounts(id: String): List<BitcoinAccountRecord> {
        val bytes = session.getRecord(id, WalletRecords.BITCOIN) ?: return emptyList()
        return try {
            WalletRecords.decodeBitcoinAccounts(bytes)
        } catch (e: IOException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    /**
     * A Bitcoin Bulk Run: adds [count] accounts of [scriptType] on [network] to wallet [id], numbered after the ones it already has
     * for that Script Type and network, and returns them. Every account is derived before anything is saved, so a failure stops the
     * run with nothing written. The record is written under the session's lock. If the wallet's Bitcoin accounts changed while the run
     * was deriving, nothing is saved and the result is null. [onProgress] receives the number of accounts derived so far.
     *
     * @throws IllegalArgumentException if [count] is outside 1 to [cap], the wallet is missing, it is a Single Key wallet, which has no
     * seed, or it would keep more than [BITCOIN_MAX_ACCOUNTS] Bitcoin accounts. Nothing is saved then.
     */
    fun addBitcoinAccounts(
        id: String,
        scriptType: ScriptType,
        network: BtcNetwork,
        count: Int,
        cap: Int,
        onProgress: (Int) -> Unit = {},
    ): List<BitcoinAccountRecord>? {
        require(BulkPolicy.checkCount(count, cap) is BulkPolicy.CountCheck.Valid) {
            "A Bulk Run makes 1 to $cap accounts."
        }
        val current = requireNotNull(meta(id)) { "No wallet $id." }
        require(current.kind == WalletKind.MNEMONIC) { SINGLE_KEY_NO_BITCOIN }
        val existing = bitcoinAccounts(id)
        require(existing.size + count <= WalletRecords.BITCOIN_MAX_ACCOUNTS) {
            "A wallet keeps at most ${WalletRecords.BITCOIN_MAX_ACCOUNTS} Bitcoin accounts."
        }
        val indices = BulkPolicy.indexRange(nextBitcoinIndex(existing, scriptType, network), count)
        val secret = mnemonicSecret(id)
        val seed = Mnemonic.of(secret.words).use { it.deriveSeed(secret.passphrase.orEmpty()) }
        val derived = try {
            bitcoinAccountRecords(seed, network, scriptType, indices, onProgress)
        } finally {
            seed.fill(0)
        }
        return synchronized(session) {
            // The profiles are read again here, so a name saved while the run derived is kept. A change of accounts is not.
            val current = bitcoinAccounts(id)
            if (current.map { it.key } != existing.map { it.key }) {
                null
            } else {
                putBitcoinAccounts(id, current + derived)
                derived
            }
        }
    }

    /**
     * The public keys of the Solana accounts of wallet [id], in account order: account N is the key at position N. A wallet with none, or
     * with a record that cannot be read, has an empty list. The record holds no secret.
     */
    fun solanaKeys(id: String): List<ByteArray> {
        val bytes = session.getRecord(id, WalletRecordNames.SOLANA) ?: return emptyList()
        return try {
            WalletRecords.decodeSolanaKeys(bytes)
        } catch (e: IOException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    /**
     * A Solana Bulk Run: adds [count] Solana accounts to wallet [id], numbered after the ones it already has, and returns their public keys.
     * Each key is m/44'/501'/N' from the seed, and the seed is zeroed after the run. Every key is derived before anything is saved, so a
     * failure stops the run with nothing written. The record is written under the session's lock. If the wallet's Solana accounts changed
     * while the run derived, nothing is saved and the result is null. [onProgress] receives the number of accounts derived so far.
     *
     * @throws IllegalArgumentException if [count] is outside 1 to [cap], the wallet is missing, it is a Single Key wallet, which has no
     * seed, or it would keep more than [WalletRecords.SOLANA_MAX_ACCOUNTS] Solana accounts. Nothing is saved then.
     */
    fun addSolanaAccounts(id: String, count: Int, cap: Int, onProgress: (Int) -> Unit = {}): List<ByteArray>? {
        require(BulkPolicy.checkCount(count, cap) is BulkPolicy.CountCheck.Valid) {
            "A Bulk Run makes 1 to $cap accounts."
        }
        val current = requireNotNull(meta(id)) { "No wallet $id." }
        require(current.kind == WalletKind.MNEMONIC) { SINGLE_KEY_NO_SOLANA }
        val start = solanaKeys(id).size
        require(start + count <= WalletRecords.SOLANA_MAX_ACCOUNTS) {
            "A wallet keeps at most ${WalletRecords.SOLANA_MAX_ACCOUNTS} Solana accounts."
        }
        val seed = seedOf(id)
        val derived = try {
            (start until start + count).mapIndexed { done, index ->
                SolanaAccount.publicKey(seed, index).also { onProgress(done + 1) }
            }
        } finally {
            seed.fill(0)
        }
        return synchronized(session) {
            val now = solanaKeys(id)
            if (now.size != start) {
                null
            } else {
                putSolanaKeys(id, now + derived)
                derived
            }
        }
    }

    /**
     * Writes the Solana record of wallet [id] as [keys]. The record has a fixed size, so a write that would not fit is refused before anything
     * is written, and the record is left as it was.
     */
    private fun putSolanaKeys(id: String, keys: List<ByteArray>) {
        val bytes = WalletRecords.encodeSolanaKeys(keys)
        require(bytes.size <= WalletRecordNames.SOLANA_CAPACITY) { NO_ROOM_FOR_SOLANA }
        session.putRecord(id, WalletRecordNames.SOLANA, bytes)
    }

    /**
     * The public keys of the Algorand accounts of wallet [id], in account order: account N is the key at position N. A wallet with none, or
     * with a record that cannot be read, has an empty list. The record holds no secret.
     */
    fun algorandKeys(id: String): List<ByteArray> {
        val bytes = session.getRecord(id, WalletRecordNames.ALGORAND) ?: return emptyList()
        return try {
            WalletRecords.decodeAlgorandKeys(bytes)
        } catch (e: IOException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    /**
     * An Algorand Bulk Run: adds [count] Algorand accounts to wallet [id], numbered after the ones it already has, and returns their public keys.
     * Account N is the Ledger Algorand key of m/44'/283'/N'/0/0 ([AlgorandAccount.publicKey]), and the seed is zeroed after the run. Every key is
     * derived before anything is saved, so a failure stops the run with nothing written. The record is written under the session's lock. If the
     * wallet's Algorand accounts changed while the run derived, nothing is saved and the result is null. [onProgress] receives the number of
     * accounts derived so far.
     *
     * @throws IllegalArgumentException if [count] is outside 1 to [cap], the wallet is missing, it is a Single Key wallet, which has no seed, or
     * it would keep more than [WalletRecords.ALGORAND_MAX_ACCOUNTS] Algorand accounts. Nothing is saved then.
     */
    fun addAlgorandAccounts(id: String, count: Int, cap: Int, onProgress: (Int) -> Unit = {}): List<ByteArray>? {
        require(BulkPolicy.checkCount(count, cap) is BulkPolicy.CountCheck.Valid) {
            "A Bulk Run makes 1 to $cap accounts."
        }
        val current = requireNotNull(meta(id)) { "No wallet $id." }
        require(current.kind == WalletKind.MNEMONIC) { SINGLE_KEY_NO_ALGORAND }
        val start = algorandKeys(id).size
        require(start + count <= WalletRecords.ALGORAND_MAX_ACCOUNTS) {
            "A wallet keeps at most ${WalletRecords.ALGORAND_MAX_ACCOUNTS} Algorand accounts."
        }
        val seed = seedOf(id)
        val derived = try {
            (start until start + count).mapIndexed { done, index ->
                AlgorandAccount.publicKey(seed, index).also { onProgress(done + 1) }
            }
        } finally {
            seed.fill(0)
        }
        return synchronized(session) {
            val now = algorandKeys(id)
            if (now.size != start) {
                null
            } else {
                putAlgorandKeys(id, now + derived)
                derived
            }
        }
    }

    /**
     * Writes the Algorand record of wallet [id] as [keys]. The record has a fixed size, so a write that would not fit is refused before anything
     * is written, and the record is left as it was.
     */
    private fun putAlgorandKeys(id: String, keys: List<ByteArray>) {
        val bytes = WalletRecords.encodeAlgorandKeys(keys)
        require(bytes.size <= WalletRecordNames.ALGORAND_CAPACITY) { NO_ROOM_FOR_ALGORAND }
        session.putRecord(id, WalletRecordNames.ALGORAND, bytes)
    }

    /**
     * Saves the profile of Bitcoin account [account] of wallet [id]: its Display Name, colour, icon, label and group, as an Ethereum account's
     * profile is saved. The account is found by its key, and its address and every other account keep what they were. A blank name is the
     * Auto-Name, as it is for an Ethereum account.
     *
     * @throws IllegalArgumentException if a name, label or group is not valid, the wallet holds no such account, or the Bitcoin record would
     * no longer fit. Nothing is saved then.
     */
    fun saveBitcoinProfile(id: String, account: BitcoinAccountRecord, profile: AccountProfile) {
        val clean = profile.checked()
        synchronized(session) {
            val current = bitcoinAccounts(id)
            require(current.any { it.key == account.key }) { "Wallet $id holds no such Bitcoin account." }
            putBitcoinAccounts(id, current.map { if (it.key == account.key) it.copy(profile = clean) else it })
        }
    }

    /**
     * Writes the Bitcoin record of wallet [id] as [accounts]. The record has a fixed size, so a write that would not fit is refused before
     * anything is written, and the record is left as it was.
     */
    private fun putBitcoinAccounts(id: String, accounts: List<BitcoinAccountRecord>) {
        val bytes = WalletRecords.encodeBitcoinAccounts(accounts)
        require(bytes.size <= WalletRecordNames.BITCOIN_CAPACITY) { NO_ROOM_FOR_BITCOIN }
        session.putRecord(id, WalletRecords.BITCOIN, bytes)
    }

    /**
     * The frames of the AirVault pairing of wallet [id]: one `ur:x-airvault-pairing` that holds the keys and accounts of every App in
     * [WalletApp] that the pairing carries, in its order, with the names, labels and groups Lensly shows for them. Lumio replaces what it holds for the wallet with
     * it, so Lensly is the source of truth. The Address Book goes with it, the first [PairingCodec.MAX_ADDRESS_BOOK] entries in the order they
     * were added. A book that cannot be read fails the pairing, so Lumio never replaces its book with an empty one. The seed is derived for
     * the pairing and zeroed after it. [brotli] compresses the body of the code: Lensly passes its encoder, and null stores the body as it is,
     * which the JVM tests use. The pairing asks for no App Password, and it carries public data only.
     *
     * @throws IllegalArgumentException if the wallet is missing or is a Single Key, which has no account key to pair.
     */
    fun pairingParts(id: String, brotli: BrotliCompressor? = BrotliEncoder): List<String> {
        val meta = requireNotNull(meta(id)) { "No wallet $id." }
        require(meta.kind == WalletKind.MNEMONIC) { "A Single Key has no account key to pair." }
        val secret = mnemonicSecret(id)
        val seed = Mnemonic.of(secret.words).use { it.deriveSeed(secret.passphrase.orEmpty()) }
        try {
            val fingerprint = HdWallet(seed).masterFingerprint.toLong() and UINT32_MASK
            val apps = WalletApp.entries.mapNotNull { app -> app.pairedApp(this, id, seed) }
            val name = fitBytes(meta.name.ifBlank { DEFAULT_PAIRING_NAME }, PairingCodec.MAX_NAME_BYTES)
            val autoNameBase = fitBytes(meta.autoNameBase, PairingCodec.MAX_NAME_BYTES)
            val book = AddressBookStore(session).load().take(PairingCodec.MAX_ADDRESS_BOOK)
            val payload = PairingPayload(fingerprint = fingerprint, name = name, autoNameBase = autoNameBase, apps = apps, addressBook = book)
            return PairingCodec.urParts(payload, brotli)
        } finally {
            seed.fill(0)
        }
    }

    /**
     * Reviews a Bitcoin request [psbt] against wallet [id]. The request must spend from exactly one of the wallet's Bitcoin accounts,
     * and the review is what the owner reads before signing. The seed is derived for the check and zeroed after it. The review holds
     * public data only, so no App Password is needed for it.
     *
     * @throws BtcRefusedException when a rule refuses the request. Nothing is derived for signing then.
     * @throws IllegalArgumentException if the wallet is missing or is a Single Key, which has no Bitcoin account.
     */
    fun bitcoinReview(
        id: String,
        psbt: Psbt,
        inputPolicy: InputVerificationPolicy,
        sourcePolicy: SigningSourcePolicy,
        feeThresholds: BtcFeeThresholds = BtcFeeThresholds.DEFAULT,
        book: List<AddressBookEntry> = emptyList(),
    ): BitcoinReviewed {
        val stored = bitcoinAccounts(id)
        if (stored.isEmpty()) throw BtcRefusedException(BtcRefusal.NoStoredAccount)
        val seed = seedOf(id)
        try {
            val accounts = stored.map { BtcAccount.fromSeed(seed, it.network, it.scriptType, it.index) }
            val account = BtcAccountSelection.select(accounts, psbt)
            val keys = WalletKeyAccess(seed)
            val review = BtcSigner(
                account.network,
                account.scriptType,
                account.accountIndex,
                account.masterFingerprint,
                keys,
                inputPolicy,
                sourcePolicy,
                feeThresholds,
            ).review(psbt)
            val record = stored.first { it.key == Triple(account.scriptType, account.network, account.accountIndex) }
            val internal = internalAccountsOf(this, id) + ownBitcoinAccounts(id, stored, accounts)
            val notes = addressNotesOf(review.outputs.mapNotNull { it.address }, BITCOIN_FAMILY, internal, book)
            val owner = meta(id)
            val signer = SigningAccount(
                name = record.profile.name ?: autoAccountName(owner?.autoNameBase.orEmpty(), record.index),
                walletName = owner?.name.orEmpty(),
                address = record.firstReceiveAddress,
            )
            return BitcoinReviewed(record, review, signer, notes)
        } finally {
            seed.fill(0)
        }
    }

    /**
     * The Bitcoin accounts of wallet [id] that [accounts] derives, keyed by the receive and change addresses within the gap limit. Only
     * this wallet's accounts are read, since only its seed is open for a review.
     */
    private fun ownBitcoinAccounts(id: String, stored: List<BitcoinAccountRecord>, accounts: List<BtcAccount>): Map<String, InternalAccount> {
        val base = meta(id)?.autoNameBase.orEmpty()
        return buildMap {
            stored.zip(accounts).forEach { (record, account) ->
                val internal = InternalAccount(record.profile.name, autoAccountName(base, record.index), record.profile.label)
                for (index in 0 until BtcAccount.GAP_LIMIT) {
                    put(addressKeyOf(account.receiveAddress(index)), internal)
                    put(addressKeyOf(account.changeAddress(index)), internal)
                }
            }
        }
    }

    /**
     * Runs [use] with the key access of wallet [id] (spec 3.2). Each call derives the key it asks for from the wallet's seed. The seed is derived
     * here and zeroed before this returns, on every path. A Single Key wallet has no seed: its one key is the account of [EvmSigner.singleKeyPath],
     * so [use] gets a [SingleKeyAccess] of that key, and the key is zeroed before this returns.
     */
    fun <T> withKeys(id: String, use: (KeyAccess) -> T): T = when (val secret = requireNotNull(secret(id)) { "No wallet $id." }) {
        is WalletSecret.Mnemonic -> {
            val seed = Mnemonic.of(secret.words).use { it.deriveSeed(secret.passphrase.orEmpty()) }
            try {
                use(WalletKeyAccess(seed, requireNotNull(meta(id)) { "No wallet $id." }.accountCount))
            } finally {
                seed.fill(0)
            }
        }
        is WalletSecret.SingleKey -> try {
            use(SingleKeyAccess(secret.privateKey, EvmSigner.singleKeyPath))
        } finally {
            secret.privateKey.fill(0)
        }
    }

    /** The BIP39 seed of wallet [id], which the caller zeroes. Throws for a wallet that is missing or is a Single Key. */
    private fun seedOf(id: String): ByteArray {
        val secret = mnemonicSecret(id)
        return Mnemonic.of(secret.words).use { it.deriveSeed(secret.passphrase.orEmpty()) }
    }

    /**
     * Addresses of wallet [id], in account order, derived from the secret. A Mnemonic wallet gives the accounts 0 to
     * [count] - 1. A Single Key wallet has one account, so [count] must be 1, and the list holds the key's address.
     */
    fun addresses(id: String, count: Int): List<String> {
        return when (val secret = requireNotNull(secret(id)) { "No wallet $id." }) {
            is WalletSecret.Mnemonic -> {
                val seed = Mnemonic.of(secret.words).use { it.deriveSeed(secret.passphrase.orEmpty()) }
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
     * The address of account [index] of wallet [id]. A Mnemonic wallet derives it from the public account key, so the
     * secret is not read. A Single Key wallet has one account, and its address comes from its key.
     *
     * @throws IllegalArgumentException if the wallet does not exist or has no account [index].
     */
    fun accountAddress(id: String, index: Int): String {
        val meta = requireNotNull(meta(id)) { "No wallet $id." }
        require(index in 0 until meta.accountCount) { "No account $index in wallet $id." }
        return when (meta.kind) {
            WalletKind.MNEMONIC -> AccountAddresses.addressAt(meta.accountKeys[index], 0)
            WalletKind.SINGLE_KEY -> addresses(id, count = 1).single()
        }
    }

    /**
     * The Ethereum view of wallet [id]: its metadata, its account profiles and the address of every account, read in two batches. The
     * metadata and base name come in the first, and the profiles in the second, so the Keystore unwraps the wallet's key twice in all,
     * not once per account. A Mnemonic wallet's addresses come from its public account key, and a Single Key wallet's from its key,
     * which is read and zeroed here. Null if the wallet does not exist or its metadata cannot be read.
     */
    fun ethereumAccounts(id: String): EthereumAccounts? {
        val head = session.getRecords(id, listOf(WalletRecords.META, WalletRecords.BASE_NAME))
        val metaBytes = head[WalletRecords.META] ?: return null
        val meta = try {
            WalletRecords.decodeMeta(id, metaBytes)
        } catch (e: IOException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        val wallet = meta.copy(autoNameBase = decodedBaseName(head[WalletRecords.BASE_NAME]) ?: return null)
        val count = wallet.accountCount
        val profileBytes = session.getRecords(id, (0 until count).map { index -> WalletRecords.accountRecord(index) })
        val profiles = (0 until count).mapNotNull { index ->
            decodedProfile(profileBytes[WalletRecords.accountRecord(index)]).takeUnless { it.isEmpty }?.let { index to it }
        }.toMap()
        val addresses = when (wallet.kind) {
            WalletKind.MNEMONIC -> wallet.accountKeys.map { key -> AccountAddresses.addressAt(key, 0) }
            // A Single Key wallet has exactly one account, and its address comes from its key. The key is zeroed by addresses().
            WalletKind.SINGLE_KEY -> if (count == 1) addresses(id, count = 1) else emptyList()
        }
        return EthereumAccounts(wallet, profiles, addresses)
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
            val updated = current.copy(name = clean)
            session.putRecord(id, WalletRecords.META, WalletRecords.encodeMeta(updated))
            updated
        }
    }

    /**
     * Records that the owner has backed up the seed phrase of wallet [id], and returns the updated metadata. A wallet that is backed up
     * already is returned as it is, and nothing is written.
     *
     * @throws IllegalArgumentException if the wallet does not exist.
     */
    fun markSeedBackedUp(id: String): WalletMeta = synchronized(session) {
        val current = requireNotNull(meta(id)) { "No wallet $id." }
        if (current.seedBackedUp) {
            current
        } else {
            val updated = current.copy(seedBackedUp = true)
            session.putRecord(id, WalletRecords.META, WalletRecords.encodeMeta(updated))
            updated
        }
    }

    /**
     * Crypto-shreds wallet [id]: its folder's Keystore key goes first, so its records cannot be read even if a file survives. The
     * other wallets are not touched, and an id with no wallet changes nothing.
     *
     * @throws java.io.IOException if a file cannot be overwritten or removed.
     * @throws app.lensly.core.vault.VaultException if the Keystore could not delete the key. The wallet is removed from the list all the
     * same, and the next unlock deletes the key.
     */
    fun delete(id: String) {
        session.deleteWallet(id)
    }

    /** The name the wallet had at creation, from its record, or null if it has no readable record. */
    private fun storedBaseName(id: String): String? = decodedBaseName(session.getRecord(id, WalletRecords.BASE_NAME))

    private fun decodedBaseName(bytes: ByteArray?): String? {
        if (bytes == null) return null
        return try {
            WalletRecords.decodeName(bytes)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * The profile of account [index] of wallet [id]: its Display Name, colour, icon, label and group. A null field is the
     * default. An account with no record, or with a record that cannot be read, has an empty profile and shows its defaults.
     */
    fun accountProfile(id: String, index: Int): AccountProfile =
        decodedProfile(session.getRecord(id, WalletRecords.accountRecord(index)))

    private fun decodedProfile(bytes: ByteArray?): AccountProfile {
        if (bytes == null) return AccountProfile()
        return try {
            WalletRecords.decodeAccountProfile(bytes)
        } catch (e: IOException) {
            AccountProfile()
        } catch (e: IllegalArgumentException) {
            AccountProfile()
        }
    }

    /**
     * The Display Name of account [index] of wallet [id], or null when the account keeps its Auto-Name. A record that
     * cannot be read counts as no name, so the account shows its Auto-Name.
     */
    fun accountName(id: String, index: Int): String? = accountProfile(id, index).name

    /** The profiles of the accounts 0 until [count] of wallet [id] that have one, by account index. */
    fun accountProfiles(id: String, count: Int): Map<Int, AccountProfile> =
        (0 until count)
            .mapNotNull { index -> accountProfile(id, index).takeUnless { it.isEmpty }?.let { index to it } }
            .toMap()

    /** The Display Names of the accounts 0 until [count] of wallet [id] that have one, by account index. */
    fun accountNames(id: String, count: Int): Map<Int, String> =
        accountProfiles(id, count).mapNotNull { (index, profile) -> profile.name?.let { index to it } }.toMap()

    /**
     * Saves the whole profile of account [index] of wallet [id], in one record: its Display Name, colour, icon, label and
     * group. A null field is the default, and an empty profile removes the record. The account's address and every other
     * account stay as they are.
     *
     * @throws IllegalArgumentException if the name, label or group is not valid, or the wallet has no account [index].
     * Nothing is saved then.
     */
    fun saveAccountProfile(id: String, index: Int, profile: AccountProfile) {
        val clean = profile.checked()
        synchronized(session) {
            requireAccount(id, index)
            writeAccountProfile(id, index, clean)
        }
    }

    /**
     * Gives account [index] of wallet [id] the Display Name [name], trimmed. Its colour, icon, label and group are kept. The
     * account's address and every other account stay as they are.
     *
     * @throws IllegalArgumentException if [name] is not a valid Display Name, or the wallet has no account [index].
     */
    fun renameAccount(id: String, index: Int, name: String) {
        val clean = displayNameOf(name)
        synchronized(session) {
            requireAccount(id, index)
            writeAccountProfile(id, index, accountProfile(id, index).copy(name = clean))
        }
    }

    /**
     * Gives account [index] of wallet [id] its Auto-Name back. Its colour, icon, label and group are kept. The record is
     * removed once nothing else is left in it, so an account with no other data is as it was before it was renamed.
     *
     * @throws IllegalArgumentException if the wallet has no account [index].
     */
    fun resetAccountName(id: String, index: Int) {
        synchronized(session) {
            requireAccount(id, index)
            writeAccountProfile(id, index, accountProfile(id, index).copy(name = null))
        }
    }

    /**
     * Batch Rename: gives each account in [indices] of wallet [id] the Display Name "<[base]> <index>", built as an Auto-Name
     * is, in one atomic write. The other accounts keep what they had, and the style of the renamed ones is kept too. [base]
     * is trimmed first.
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
            val (puts, deletes) = batchWrites(id, accounts) { index, profile ->
                profile.copy(name = autoAccountName(clean, index))
            }
            session.putRecords(id, puts, deletes)
        }
    }

    /**
     * Batch reset: gives each account in [indices] of wallet [id] its Auto-Name back, in one atomic write. The other accounts
     * keep what they had, and the style of the reset ones is kept too. A record that holds nothing else is removed.
     *
     * @throws IllegalArgumentException if [indices] is empty or an index is not an account of the wallet. Nothing is changed.
     */
    fun resetAccountNames(id: String, indices: Collection<Int>) {
        val accounts = selectedAccounts(indices)
        synchronized(session) {
            requireAccounts(id, accounts)
            val (puts, deletes) = batchWrites(id, accounts) { _, profile -> profile.copy(name = null) }
            session.putRecords(id, puts, deletes)
        }
    }

    /**
     * Batch styling: applies [changes], in order, to each account in [indices] of wallet [id], in one atomic write. Each account
     * keeps its Display Name, and every part of its style that no change touches.
     *
     * @throws IllegalArgumentException if [indices] or [changes] is empty, a label or group is not valid, or an index is not an
     * account of the wallet. Nothing is saved then.
     */
    fun changeAccountStyles(id: String, indices: Collection<Int>, changes: List<StyleChange>) {
        val accounts = selectedAccounts(indices)
        require(changes.isNotEmpty()) { "Choose at least one change." }
        val checked = changes.map { it.checked() }
        synchronized(session) {
            requireAccounts(id, accounts)
            val (puts, deletes) = batchWrites(id, accounts) { _, profile -> profile.applying(checked) }
            session.putRecords(id, puts, deletes)
        }
    }

    /** The profile of wallet [id]: its colour, icon, label and groups. Empty when it has none or its record cannot be read. */
    fun walletProfile(id: String): WalletProfile {
        val bytes = session.getRecord(id, WalletRecords.PROFILE) ?: return WalletProfile()
        return try {
            WalletRecords.decodeWalletProfile(bytes)
        } catch (e: IOException) {
            WalletProfile()
        } catch (e: IllegalArgumentException) {
            WalletProfile()
        }
    }

    /**
     * Saves the profile of wallet [id]. Its name is not part of it, so [renameWallet] still changes the name and the accounts
     * keep theirs. An empty profile removes the record.
     *
     * @throws IllegalArgumentException if a label or group is not valid, there are more than [WALLET_MAX_GROUPS] groups, or the
     * wallet does not exist. Nothing is saved then.
     */
    fun saveWalletProfile(id: String, profile: WalletProfile) {
        val clean = profile.checked()
        synchronized(session) {
            requireNotNull(meta(id)) { "No wallet $id." }
            if (clean.isEmpty) {
                session.deleteRecord(id, WalletRecords.PROFILE)
            } else {
                session.putRecord(id, WalletRecords.PROFILE, WalletRecords.encodeWalletProfile(clean))
            }
        }
    }

    /** Stores [profile] as the record of account [index], or removes the record when the profile is empty. */
    private fun writeAccountProfile(id: String, index: Int, profile: AccountProfile) {
        val record = WalletRecords.accountRecord(index)
        if (profile.isEmpty) {
            session.deleteRecord(id, record)
        } else {
            session.putRecord(id, record, WalletRecords.encodeAccountProfile(profile))
        }
    }

    /**
     * The record writes that give each account in [accounts] the profile [change] makes of its current one: a put for a profile
     * that is still set, and a delete for one that is empty. The caller holds the session lock, and applies the writes with one
     * [VaultSession.putRecords] call, so they all land or none does.
     */
    private inline fun batchWrites(
        id: String,
        accounts: List<Int>,
        change: (index: Int, profile: AccountProfile) -> AccountProfile,
    ): Pair<Map<String, ByteArray>, List<String>> {
        val puts = linkedMapOf<String, ByteArray>()
        val deletes = mutableListOf<String>()
        accounts.forEach { index ->
            val profile = change(index, accountProfile(id, index))
            val record = WalletRecords.accountRecord(index)
            if (profile.isEmpty) deletes += record else puts[record] = WalletRecords.encodeAccountProfile(profile)
        }
        return puts to deletes
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

    private class Identity(val fingerprintHex: String, val keys: List<AccountXpub>)

    /** The public account key of account [account] of wallet [id], derived from its seed. The seed is zeroed here. */
    private fun accountKeyOf(id: String, account: Int): AccountXpub {
        val seed = seedOf(id)
        try {
            return HdWallet(seed).accountXpub(account)
        } finally {
            seed.fill(0)
        }
    }

    private fun identityOf(words: List<String>, passphrase: String): Identity {
        val seed = Mnemonic.toSeed(words, passphrase)
        try {
            val wallet = HdWallet(seed)
            return Identity(wallet.masterFingerprintHex, listOf(wallet.accountXpub(0)))
        } finally {
            seed.fill(0)
        }
    }

    private companion object {
        const val SINGLE_KEY_ONE_ACCOUNT = "A Single Key wallet has exactly one account and cannot generate more."
        const val NO_ROOM_FOR_BITCOIN =
            "The Bitcoin accounts of this wallet take too much room to save. Clear a name, label or group on one of them."
        const val NO_ROOM_FOR_SOLANA = "The Solana accounts of this wallet take too much room to save."
        const val SINGLE_KEY_NO_SOLANA = "A Single Key wallet has no seed, so it cannot hold Solana accounts."
        const val NO_ROOM_FOR_ALGORAND = "The Algorand accounts of this wallet take too much room to save."
        const val SINGLE_KEY_NO_ALGORAND = "A Single Key wallet has no seed, so it cannot hold Algorand accounts."
    }
}

/** Binary encodings of the two wallet records. See [WalletStore] for the layout. */
internal object WalletRecords {
    const val META = "meta"
    const val SECRET = "secret"

    /** The record that holds the wallet's name at creation, which its Auto-Names are built from. */
    const val BASE_NAME = "base-name"

    /** The record that holds the wallet's profile: its colour, icon, label and groups. Only a wallet with one has it. */
    const val PROFILE = "profile"

    /** The record that holds the wallet's Bitcoin accounts. Only a wallet with one has it. */
    const val BITCOIN = "bitcoin"

    /** The most Bitcoin accounts a wallet keeps, the largest Bulk Cap. The Bitcoin record's capacity is sized for them. */
    const val BITCOIN_MAX_ACCOUNTS = 100

    /** The most Solana accounts a wallet keeps, the largest Bulk Cap. The Solana record's capacity is sized for them. */
    const val SOLANA_MAX_ACCOUNTS = 100

    /** The most Algorand accounts a wallet keeps, the largest Bulk Cap. The Algorand record's capacity is sized for them. */
    const val ALGORAND_MAX_ACCOUNTS = 100

    /** The version of the Solana record: a count, then the 32-byte public key of each account. */
    private const val SOLANA_VERSION = 1
    private const val SOLANA_KEY_BYTES = 32

    /** The version of the Algorand record: a count, then the 32-byte public key of each account. */
    private const val ALGORAND_VERSION = 1
    private const val ALGORAND_KEY_BYTES = 32

    /** The version of a meta record. */
    private const val META_VERSION = 4

    /** The secret version of a Mnemonic wallet. A Single Key wallet's secret is version 2. */
    private const val MNEMONIC_SECRET_VERSION = 1
    private const val SINGLE_KEY_SECRET_VERSION = 2
    private const val NAME_VERSION = 1

    /** The version of an account profile. */
    private const val ACCOUNT_PROFILE_VERSION = 2
    private const val WALLET_PROFILE_VERSION = 1

    /** The version of the Bitcoin record, which holds each account's profile. */
    private const val BITCOIN_VERSION = 2

    /** The fields of an account profile, one bit each. The flags byte says which ones follow, in this order. */
    private const val FIELD_NAME = 1
    private const val FIELD_COLOUR = 2
    private const val FIELD_ICON = 4
    private const val FIELD_LABEL = 8
    private const val FIELD_GROUP = 16
    private const val ACCOUNT_FIELDS = FIELD_NAME or FIELD_COLOUR or FIELD_ICON or FIELD_LABEL or FIELD_GROUP

    /** The fields of a wallet profile that have a flag. Its groups always follow, as a count and then the names. */
    private const val WALLET_FIELD_COLOUR = 1
    private const val WALLET_FIELD_ICON = 2
    private const val WALLET_FIELD_LABEL = 4
    private const val WALLET_FIELDS = WALLET_FIELD_COLOUR or WALLET_FIELD_ICON or WALLET_FIELD_LABEL

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

    /**
     * An account profile record: the version byte, a flags byte, then each field the flags name, in the order of the
     * [FIELD_NAME] bits. A colour and an icon take one byte each, as their codes.
     */
    fun encodeAccountProfile(profile: AccountProfile): ByteArray = writeBytes { out -> writeAccountProfile(out, profile) }

    /** Writes [profile] as an account record does: the version byte, a flags byte, then each field the flags name. */
    private fun writeAccountProfile(out: DataOutputStream, profile: AccountProfile) {
        out.writeByte(ACCOUNT_PROFILE_VERSION)
        out.writeByte(
            fieldBit(profile.name != null, FIELD_NAME) or
                fieldBit(profile.colour != null, FIELD_COLOUR) or
                fieldBit(profile.icon != null, FIELD_ICON) or
                fieldBit(profile.label != null, FIELD_LABEL) or
                fieldBit(profile.group != null, FIELD_GROUP),
        )
        profile.name?.let { out.writeUTF(it) }
        profile.colour?.let { out.writeByte(it.wire) }
        profile.icon?.let { out.writeByte(it.wire) }
        profile.label?.let { out.writeUTF(it) }
        profile.group?.let { out.writeUTF(it) }
    }

    /**
     * Reads an account profile. A colour or icon code this build does not know reads as the default.
     */
    fun decodeAccountProfile(bytes: ByteArray): AccountProfile = readBytes(bytes) { input -> readAccountProfile(input) }

    /** Reads one account profile from [input], in the layout [writeAccountProfile] writes. */
    private fun readAccountProfile(input: DataInputStream): AccountProfile {
        val version = input.readUnsignedByte()
        require(version == ACCOUNT_PROFILE_VERSION) { "Unknown record version $version." }
        val fields = input.readUnsignedByte()
        require(fields and ACCOUNT_FIELDS.inv() == 0) { "Unknown profile field." }
        val name = if (fields has FIELD_NAME) displayNameOf(input.readUTF()) else null
        val colour = if (fields has FIELD_COLOUR) ProfileColour.fromWire(input.readUnsignedByte()) else null
        val icon = if (fields has FIELD_ICON) ProfileIcon.fromWire(input.readUnsignedByte()) else null
        val label = if (fields has FIELD_LABEL) profileTextOf(input.readUTF()) else null
        val group = if (fields has FIELD_GROUP) profileTextOf(input.readUTF()) else null
        return AccountProfile(name = name, colour = colour, icon = icon, label = label, group = group)
    }

    /**
     * A wallet profile record: the version byte, a flags byte for the colour, icon and label, those fields that are set, then
     * the group count and each group.
     */
    fun encodeWalletProfile(profile: WalletProfile): ByteArray {
        require(profile.groups.size <= WALLET_MAX_GROUPS) { "A wallet sits in at most $WALLET_MAX_GROUPS groups." }
        return writeBytes { out ->
            out.writeByte(WALLET_PROFILE_VERSION)
            out.writeByte(
                fieldBit(profile.colour != null, WALLET_FIELD_COLOUR) or
                    fieldBit(profile.icon != null, WALLET_FIELD_ICON) or
                    fieldBit(profile.label != null, WALLET_FIELD_LABEL),
            )
            profile.colour?.let { out.writeByte(it.wire) }
            profile.icon?.let { out.writeByte(it.wire) }
            profile.label?.let { out.writeUTF(it) }
            out.writeByte(profile.groups.size)
            profile.groups.forEach { out.writeUTF(it) }
        }
    }

    fun decodeWalletProfile(bytes: ByteArray): WalletProfile = readBytes(bytes) { input ->
        val version = input.readUnsignedByte()
        require(version == WALLET_PROFILE_VERSION) { "Unknown record version $version." }
        val fields = input.readUnsignedByte()
        require(fields and WALLET_FIELDS.inv() == 0) { "Unknown profile field." }
        val colour = if (fields has WALLET_FIELD_COLOUR) ProfileColour.fromWire(input.readUnsignedByte()) else null
        val icon = if (fields has WALLET_FIELD_ICON) ProfileIcon.fromWire(input.readUnsignedByte()) else null
        val label = if (fields has WALLET_FIELD_LABEL) profileTextOf(input.readUTF()) else null
        val count = input.readUnsignedByte()
        require(count <= WALLET_MAX_GROUPS) { "Too many groups." }
        val groups = List(count) { profileTextOf(input.readUTF()) }
        WalletProfile(colour = colour, icon = icon, label = label, groups = groups)
    }

    /**
     * The Bitcoin accounts record: the version byte, the count (int), then each account: its network as its BIP44 coin type (byte),
     * its Script Type as its BIP43 purpose (byte), its index (int), its first receive address (UTF), and whether it has a profile (a
     * byte, 1 when it does), with the profile after it. Two accounts with one Script Type, network and index are refused, both when
     * written and when read.
     */
    fun encodeBitcoinAccounts(accounts: List<BitcoinAccountRecord>): ByteArray {
        require(accounts.map { it.key }.distinct().size == accounts.size) {
            "A Bitcoin account is unique per Script Type, network and index."
        }
        require(accounts.size <= BITCOIN_MAX_ACCOUNTS) { "A wallet keeps at most $BITCOIN_MAX_ACCOUNTS Bitcoin accounts." }
        return writeBytes { out ->
            out.writeByte(BITCOIN_VERSION)
            out.writeInt(accounts.size)
            accounts.forEach { account ->
                out.writeByte(account.network.coinType)
                out.writeByte(account.scriptType.purpose)
                out.writeInt(account.index)
                out.writeUTF(account.firstReceiveAddress)
                out.writeBoolean(!account.profile.isEmpty)
                if (!account.profile.isEmpty) writeAccountProfile(out, account.profile)
            }
        }
    }

    /** The accounts of a Bitcoin record, with their profiles. */
    fun decodeBitcoinAccounts(bytes: ByteArray): List<BitcoinAccountRecord> = readBytes(bytes) { input ->
        val version = input.readUnsignedByte()
        require(version == BITCOIN_VERSION) { "Unknown record version $version." }
        val count = input.readInt()
        require(count >= 0) { "Account count out of range." }
        val accounts = List(count) {
            val coinType = input.readUnsignedByte()
            val network = BtcNetwork.entries.firstOrNull { it.coinType == coinType }
                ?: throw IllegalArgumentException("Unknown network.")
            val purpose = input.readUnsignedByte()
            val scriptType = ScriptType.entries.firstOrNull { it.purpose == purpose }
                ?: throw IllegalArgumentException("Unknown script type.")
            val index = input.readInt()
            require(index >= 0) { "Account index out of range." }
            val address = input.readUTF()
            val profile = if (input.readBoolean()) readAccountProfile(input) else AccountProfile()
            BitcoinAccountRecord(network, scriptType, index, address, profile)
        }
        require(accounts.map { it.key }.distinct().size == accounts.size) { "Duplicate Bitcoin account." }
        accounts
    }

    /** The Solana record: the version byte, the count (int), then the 32-byte public key of each account, in account order. */
    fun encodeSolanaKeys(keys: List<ByteArray>): ByteArray {
        require(keys.size <= SOLANA_MAX_ACCOUNTS) { "A wallet keeps at most $SOLANA_MAX_ACCOUNTS Solana accounts." }
        require(keys.all { it.size == SOLANA_KEY_BYTES }) { "A Solana public key is 32 bytes." }
        return writeBytes { out ->
            out.writeByte(SOLANA_VERSION)
            out.writeInt(keys.size)
            keys.forEach { out.write(it) }
        }
    }

    /** The public keys of a Solana record, in account order. */
    fun decodeSolanaKeys(bytes: ByteArray): List<ByteArray> = readBytes(bytes) { input ->
        val version = input.readUnsignedByte()
        require(version == SOLANA_VERSION) { "Unknown record version $version." }
        val count = input.readInt()
        require(count in 0..SOLANA_MAX_ACCOUNTS) { "Account count out of range." }
        List(count) { ByteArray(SOLANA_KEY_BYTES).also { input.readFully(it) } }
    }

    /** The Algorand record: the version byte, the count (int), then the 32-byte public key of each account, in account order. */
    fun encodeAlgorandKeys(keys: List<ByteArray>): ByteArray {
        require(keys.size <= ALGORAND_MAX_ACCOUNTS) { "A wallet keeps at most $ALGORAND_MAX_ACCOUNTS Algorand accounts." }
        require(keys.all { it.size == ALGORAND_KEY_BYTES }) { "An Algorand public key is 32 bytes." }
        return writeBytes { out ->
            out.writeByte(ALGORAND_VERSION)
            out.writeInt(keys.size)
            keys.forEach { out.write(it) }
        }
    }

    /** The public keys of an Algorand record, in account order. */
    fun decodeAlgorandKeys(bytes: ByteArray): List<ByteArray> = readBytes(bytes) { input ->
        val version = input.readUnsignedByte()
        require(version == ALGORAND_VERSION) { "Unknown record version $version." }
        val count = input.readInt()
        require(count in 0..ALGORAND_MAX_ACCOUNTS) { "Account count out of range." }
        List(count) { ByteArray(ALGORAND_KEY_BYTES).also { input.readFully(it) } }
    }

    /** The flag bit for a field that is set, or 0 for one that is not. */
    private fun fieldBit(set: Boolean, bit: Int): Int = if (set) bit else 0

    /** True when the flag [bit] is set in this flags byte. */
    private infix fun Int.has(bit: Int): Boolean = (this and bit) != 0

    fun encodeMeta(meta: WalletMeta): ByteArray = writeBytes { out ->
        out.writeByte(META_VERSION)
        out.writeByte(meta.kind.wire)
        out.writeUTF(meta.name)
        out.writeLong(meta.createdAtMs)
        out.writeBoolean(meta.deviceGenerated)
        out.writeInt(meta.accountCount)
        out.writeBoolean(meta.seedBackedUp)
        when (meta.kind) {
            WalletKind.MNEMONIC -> {
                require(meta.accountKeys.size == meta.accountCount) { "A Mnemonic wallet needs a public key for each account." }
                out.writeUTF(requireNotNull(meta.masterFingerprintHex) { "A Mnemonic wallet needs its fingerprint." })
                out.writeInt(meta.accountKeys.size)
                for (key in meta.accountKeys) {
                    writeField(out, key.publicKey)
                    writeField(out, key.chainCode)
                    out.writeInt(key.parentFingerprint)
                }
            }
            WalletKind.SINGLE_KEY -> require(
                meta.accountCount == 1 && meta.accountKeys.isEmpty() && meta.masterFingerprintHex == null,
            ) { "A Single Key wallet has one account and no account public key." }
        }
    }

    fun decodeMeta(id: String, bytes: ByteArray): WalletMeta = readBytes(bytes) { input ->
        val version = input.readUnsignedByte()
        require(version == META_VERSION) { "Unknown record version $version." }
        val kind = WalletKind.fromWire(input.readUnsignedByte())
        val name = input.readUTF()
        val createdAtMs = input.readLong()
        val deviceGenerated = input.readBoolean()
        val accountCount = input.readInt()
        val seedBackedUp = input.readBoolean()
        when (kind) {
            WalletKind.MNEMONIC -> {
                val fingerprintHex = input.readUTF()
                val keys = List(input.readInt()) {
                    val publicKey = readField(input)
                    val chainCode = readField(input)
                    AccountXpub(publicKey, chainCode, input.readInt())
                }
                require(keys.size == accountCount) { "A Mnemonic wallet needs a public key for each account." }
                WalletMeta(
                    id = id,
                    name = name,
                    createdAtMs = createdAtMs,
                    deviceGenerated = deviceGenerated,
                    accountCount = accountCount,
                    masterFingerprintHex = fingerprintHex,
                    accountKeys = keys,
                    kind = kind,
                    seedBackedUp = seedBackedUp,
                )
            }
            WalletKind.SINGLE_KEY -> WalletMeta(
                id = id,
                name = name,
                createdAtMs = createdAtMs,
                deviceGenerated = deviceGenerated,
                accountCount = accountCount,
                masterFingerprintHex = null,
                accountKeys = emptyList(),
                kind = kind,
                seedBackedUp = seedBackedUp,
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
    internal fun writeBytes(write: (DataOutputStream) -> Unit): ByteArray {
        val buffer = WipingBuffer()
        try {
            DataOutputStream(buffer).use(write)
            return buffer.toByteArray()
        } finally {
            buffer.wipe()
        }
    }

    internal inline fun <T> readBytes(bytes: ByteArray, read: (DataInputStream) -> T): T =
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
