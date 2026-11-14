package app.lensly.app

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.btc.ScriptType
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
    /**
     * True when the owner has backed up the seed phrase. The create ceremony sets it once its quiz passes, a restore sets it, and so does a
     * Single Key import. A wallet with it false is asked for a backup on Home. A record written before the flag reads as true, so no
     * wallet made before it is asked.
     */
    val seedBackedUp: Boolean = true,
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
 * Wallets kept in an open [VaultSession]. Each wallet has a meta and a secret record and a base-name record. An account with
 * a profile has one more, and so does a wallet with a profile. All of them use DataOutputStream and DataInputStream behind
 * a version byte. None is JSON.
 *
 * - `meta`, version 3: kind (byte), name (UTF), created-at ms (long), device-generated (boolean), account count (int), seed backed up
 *   (boolean). A Mnemonic wallet then has master fingerprint hex (UTF), account public key and chain code (each an int length,
 *   then bytes), and parent fingerprint (int). A Single Key wallet has nothing more.
 *   Version 2 is the same record without the backed-up flag, and it loads as backed up. Version 1 is the same record without the kind
 *   byte. It is always a Mnemonic wallet, and it still loads.
 * - `secret`, version 1 for a Mnemonic wallet: word count (byte), each word (UTF), has-passphrase (boolean), then the
 *   passphrase (UTF) when present. Version 2 for a Single Key wallet: the 32-byte private key.
 * - `account-<index>`, version 2: a flags byte, then each field the flags name, in order: the Display Name (UTF), the colour
 *   and the icon (one byte each, a code from [ProfileColour] or [ProfileIcon]), the label (UTF) and the group (UTF). Version 1
 *   is the Display Name alone (UTF), as renaming wrote it before profiles, and it still loads. An account whose fields are all
 *   defaults has no record, so an account that keeps its Auto-Name and its generated look costs nothing.
 * - `profile`, version 1, for a wallet: a flags byte for the colour, icon and label, then those fields that are set, then the
 *   group count (byte) and each group (UTF). A wallet whose fields are all defaults has no record.
 * - `base-name`, version 1: the wallet's name at creation (UTF), which its accounts' Auto-Names are built from. It is
 *   written once, at creation, and before the first rename of a wallet that has none. A wallet without one uses its
 *   current name.
 * - `bitcoin`, version 1: the count (int), then each Bitcoin account: its network as its BIP44 coin type (byte), its Script Type as
 *   its BIP43 purpose (byte), its index (int) and its first receive address (UTF). A wallet with no record has no Bitcoin account.
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
                accountXpub = identity.xpub,
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
        val seed = Mnemonic.of(secret.words).use { it.deriveSeed(secret.passphrase.orEmpty()) }
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
            if (bitcoinAccounts(id) != existing) {
                null
            } else {
                session.putRecord(id, WalletRecords.BITCOIN, WalletRecords.encodeBitcoinAccounts(existing + derived))
                derived
            }
        }
    }

    /**
     * The frames of the Bitcoin watch-only export of wallet [id]: one `ur:crypto-account` with the four Script Types of account 0 on
     * each network the wallet has a Bitcoin account on. The seed is derived for the export and zeroed after it. The export asks for no
     * App Password, as the EVM pairing does, and it carries public data only.
     *
     * @throws IllegalArgumentException if the wallet is missing, is a Single Key, or has no Bitcoin account.
     */
    fun bitcoinWatchOnlyParts(id: String): List<String> {
        val networks = bitcoinAccounts(id).map { it.network }.distinct()
        require(networks.isNotEmpty()) { "Wallet $id has no Bitcoin account." }
        val secret = mnemonicSecret(id)
        val seed = Mnemonic.of(secret.words).use { it.deriveSeed(secret.passphrase.orEmpty()) }
        try {
            val accounts = networks.flatMap { network -> BtcWatchOnly.accounts(seed, network, accountIndex = 0) }
            return BtcWatchOnly.cryptoAccountParts(accounts)
        } finally {
            seed.fill(0)
        }
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
            WalletKind.MNEMONIC -> AccountAddresses.addressAt(requireNotNull(meta.accountXpub), index)
            WalletKind.SINGLE_KEY -> addresses(id, count = 1).single()
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
     * The profile of account [index] of wallet [id]: its Display Name, colour, icon, label and group. A null field is the
     * default. An account with no record, or with a record that cannot be read, has an empty profile and shows its defaults.
     */
    fun accountProfile(id: String, index: Int): AccountProfile {
        val bytes = session.getRecord(id, WalletRecords.accountRecord(index)) ?: return AccountProfile()
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

    /** The record that holds the wallet's profile: its colour, icon, label and groups. Only a wallet with one has it. */
    const val PROFILE = "profile"

    /** The record that holds the wallet's Bitcoin accounts. Only a wallet with one has it. */
    const val BITCOIN = "bitcoin"

    /** The most Bitcoin accounts a wallet keeps, the largest Bulk Cap. The Bitcoin record's capacity is sized for them. */
    const val BITCOIN_MAX_ACCOUNTS = 100

    /** The meta versions before wallet kinds, which have no kind byte, and before the backed-up flag. Version 3 is written now. */
    private const val META_VERSION_WITHOUT_KIND = 1
    private const val META_VERSION_WITHOUT_BACKUP_FLAG = 2
    private const val META_VERSION = 3

    /** The secret version of a Mnemonic wallet. It is the only secret layout before Single Keys. */
    private const val MNEMONIC_SECRET_VERSION = 1
    private const val SINGLE_KEY_SECRET_VERSION = 2
    private const val NAME_VERSION = 1

    /** The version of an account profile that holds more than a name. Version 1 holds the Display Name alone. */
    private const val ACCOUNT_PROFILE_VERSION = 2
    private const val WALLET_PROFILE_VERSION = 1
    private const val BITCOIN_VERSION = 1

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
    fun encodeAccountProfile(profile: AccountProfile): ByteArray = writeBytes { out ->
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
     * Reads an account profile. A version 1 record is the Display Name alone, as renaming wrote it before profiles, so it
     * reads as a name with no style. A colour or icon code this build does not know reads as the default.
     */
    fun decodeAccountProfile(bytes: ByteArray): AccountProfile = readBytes(bytes) { input ->
        when (val version = input.readUnsignedByte()) {
            NAME_VERSION -> AccountProfile(name = displayNameOf(input.readUTF()))
            ACCOUNT_PROFILE_VERSION -> {
                val fields = input.readUnsignedByte()
                require(fields and ACCOUNT_FIELDS.inv() == 0) { "Unknown profile field." }
                val name = if (fields has FIELD_NAME) displayNameOf(input.readUTF()) else null
                val colour = if (fields has FIELD_COLOUR) ProfileColour.fromWire(input.readUnsignedByte()) else null
                val icon = if (fields has FIELD_ICON) ProfileIcon.fromWire(input.readUnsignedByte()) else null
                val label = if (fields has FIELD_LABEL) profileTextOf(input.readUTF()) else null
                val group = if (fields has FIELD_GROUP) profileTextOf(input.readUTF()) else null
                AccountProfile(name = name, colour = colour, icon = icon, label = label, group = group)
            }
            else -> throw IllegalArgumentException("Unknown record version $version.")
        }
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
     * its Script Type as its BIP43 purpose (byte), its index (int) and its first receive address (UTF). Two accounts with one Script
     * Type, network and index are refused, both when written and when read.
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
            }
        }
    }

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
            BitcoinAccountRecord(network, scriptType, index, input.readUTF())
        }
        require(accounts.map { it.key }.distinct().size == accounts.size) { "Duplicate Bitcoin account." }
        accounts
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
        val version = input.readUnsignedByte()
        val kind = when (version) {
            META_VERSION_WITHOUT_KIND -> WalletKind.MNEMONIC
            META_VERSION_WITHOUT_BACKUP_FLAG, META_VERSION -> WalletKind.fromWire(input.readUnsignedByte())
            else -> throw IllegalArgumentException("Unknown record version $version.")
        }
        val name = input.readUTF()
        val createdAtMs = input.readLong()
        val deviceGenerated = input.readBoolean()
        val accountCount = input.readInt()
        // A record from before the flag reads as backed up, so a wallet made before it is not asked for a backup.
        val seedBackedUp = if (version == META_VERSION) input.readBoolean() else true
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
                accountXpub = null,
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
