package app.lumio.pair

import app.lensly.app.AddressBookEntry
import app.lensly.app.export.PairingPayload
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultSession
import app.lensly.core.vault.WalletRecordNames
import app.lensly.core.wallet.AccountXpub

/** The chain families a paired wallet can hold. */
enum class PairFamily { EVM, BITCOIN, SOLANA, ALGORAND }

/**
 * One Ethereum account of a paired wallet, as Lensly names it: its number N, which is its key's account m/44'/60'/N'/0/0, the public [key] of that
 * address, and its name, label and group.
 */
data class PairedAccount(val index: Int, val key: AccountXpub, val name: String?, val label: String?, val group: String?)

/**
 * A paired wallet: one Lensly wallet, identified by its master fingerprint, with the public data of its Apps. It holds no key material. [evmAccounts]
 * are the Ethereum accounts, each with the public key of its one address. [bitcoin] holds the watch-only Bitcoin Accounts, if any.
 * [addressBook] is the Address Book that Lensly sent with the pairing, in the order it was sent. It is empty when there is none.
 * [solana] holds the public key of each Solana account, 32 bytes, in account order. Lumio shows each one's base58 address.
 * [algorand] holds the public key of each Algorand account, 32 bytes, in account order. Lumio shows each one's address.
 */
data class PairedWallet(
    val id: String,
    val name: String,
    val fingerprint: Long,
    val createdAtMs: Long,
    val bitcoin: List<BtcWatchOnlyAccount>,
    val evmAccounts: List<PairedAccount> = emptyList(),
    val addressBook: List<AddressBookEntry> = emptyList(),
    val solana: List<ByteArray> = emptyList(),
    val algorand: List<ByteArray> = emptyList(),
) {
    /** The public key of each Ethereum account, in account order. Empty when the wallet holds no Ethereum account. */
    val evmKeys: List<AccountXpub>
        get() = evmAccounts.map { it.key }

    val families: Set<PairFamily>
        get() = buildSet {
            if (evmAccounts.isNotEmpty()) add(PairFamily.EVM)
            if (bitcoin.isNotEmpty()) add(PairFamily.BITCOIN)
            if (solana.isNotEmpty()) add(PairFamily.SOLANA)
            if (algorand.isNotEmpty()) add(PairFamily.ALGORAND)
        }
}

/** The result of a save: the wallet that now holds the pairing, and whether this save created it. False means an update. */
data class PairSaved(val id: String, val created: Boolean)

/**
 * The paired wallets of an open Lumio vault. Each is one wallet folder of the vault, so its records are sealed with the wallet's own key:
 * META holds the fingerprint, the creation time, the name and the public key of each Ethereum account, each account's record holds its name,
 * label and group, and the BITCOIN record holds the watch-only Accounts. A pairing whose fingerprint is already paired replaces that wallet's
 * records, and never adds a second one.
 */
object PairedWallets {

    /**
     * The paired wallets, oldest first, each with its Address Book. A folder that does not read as a pairing is left out. A book that cannot
     * be read leaves the wallets listed without one, and the next pairing still refuses to replace a book it cannot read.
     */
    fun list(session: VaultSession): List<PairedWallet> {
        val books: Map<String, List<AddressBookEntry>> = try {
            PairAddressBooks.load(session)
        } catch (e: VaultException) {
            emptyMap()
        }
        return session.listWallets()
            .mapNotNull { id -> read(session, id)?.copy(addressBook = books[id].orEmpty()) }
            .sortedBy { it.createdAtMs }
    }

    /**
     * Stores [pairing], as Lensly exported it. Lensly is the source of truth: the names, labels and groups, the accounts, the keys and the
     * Address Book replace what Lumio held for the same fingerprint, so an account or an entry that Lensly no longer lists is removed. A new
     * fingerprint makes a new wallet.
     */
    fun save(session: VaultSession, pairing: PairingPayload, nowMs: Long): PairSaved {
        val paired = list(session).firstOrNull { it.fingerprint == pairing.fingerprint }
        val id = paired?.id ?: session.createWallet()
        try {
            session.putRecords(id, recordsFor(pairing, createdAtMs = paired?.createdAtMs ?: nowMs), deletesFor(pairing))
            PairAddressBooks.replace(session, id, pairing.addressBook)
        } catch (e: Throwable) {
            if (paired == null) session.deleteWallet(id)
            throw e
        }
        return PairSaved(id, created = paired == null)
    }

    /** Erases the paired wallet [id], its records and its Address Book. The folder keeps its shape, as every wallet folder does. */
    fun delete(session: VaultSession, id: String) {
        session.deleteWallet(id)
        PairAddressBooks.replace(session, id, emptyList())
    }

    /** The records that hold [pairing], written in one step: the META record, an account record for each Ethereum account, and the Bitcoin record. */
    private fun recordsFor(pairing: PairingPayload, createdAtMs: Long): Map<String, ByteArray> {
        val ethereum = requireNotNull(pairing.apps.firstOrNull { it.app == PairImport.ETHEREUM }) { "A validated pairing holds Ethereum." }
        val meta = PairRecords.Meta(
            fingerprint = pairing.fingerprint,
            createdAtMs = createdAtMs,
            name = pairing.name,
            accountKeys = PairImport.ethereumAccountKeysOf(pairing),
        )
        val records = mutableMapOf(WalletRecordNames.META to PairRecords.encodeMeta(meta))
        for ((index, account) in ethereum.accounts.withIndex()) {
            records[WalletRecordNames.account(index)] = PairRecords.encodeAccount(
                PairRecords.Account(name = account.name, label = account.label, group = account.group),
            )
        }
        val bitcoin = PairImport.bitcoinAccountsOf(pairing)
        if (bitcoin.isNotEmpty()) records[WalletRecordNames.BITCOIN] = PairRecords.encodeBitcoin(bitcoin)
        val solana = PairImport.solanaPublicKeysOf(pairing)
        if (solana.isNotEmpty()) records[WalletRecordNames.SOLANA] = PairRecords.encodeSolana(solana)
        val algorand = PairImport.algorandPublicKeysOf(pairing)
        if (algorand.isNotEmpty()) records[WalletRecordNames.ALGORAND] = PairRecords.encodeAlgorand(algorand)
        return records
    }

    /**
     * The records of [pairing] that a wallet holding it must not keep: the account records past its last account, and the Bitcoin, Solana and
     * Algorand records when the pairing holds none of those accounts.
     */
    private fun deletesFor(pairing: PairingPayload): List<String> {
        val count = pairing.apps.first { it.app == PairImport.ETHEREUM }.accounts.size
        val accounts = (count until PairImport.MAX_ETHEREUM_ACCOUNTS).map { WalletRecordNames.account(it) }
        val bitcoin = if (PairImport.bitcoinAccountsOf(pairing).isEmpty()) listOf(WalletRecordNames.BITCOIN) else emptyList()
        val solana = if (PairImport.solanaPublicKeysOf(pairing).isEmpty()) listOf(WalletRecordNames.SOLANA) else emptyList()
        val algorand = if (PairImport.algorandPublicKeysOf(pairing).isEmpty()) listOf(WalletRecordNames.ALGORAND) else emptyList()
        return accounts + bitcoin + solana + algorand
    }

    /** The paired wallet in folder [id], or null when the folder is not one (another folder kind, or a damaged record). */
    private fun read(session: VaultSession, id: String): PairedWallet? = try {
        val meta = session.getRecord(id, WalletRecordNames.META)?.let { PairRecords.decodeMeta(it) } ?: return null
        val accounts = meta.accountKeys.mapIndexed { index, key ->
            val account = PairRecords.decodeAccount(session.getRecord(id, WalletRecordNames.account(index)) ?: return null)
            PairedAccount(index, key, account.name, account.label, account.group)
        }
        val bitcoin = session.getRecord(id, WalletRecordNames.BITCOIN)
            ?.let { PairRecords.decodeBitcoin(it, meta.fingerprint) }
            .orEmpty()
        val solana = session.getRecord(id, WalletRecordNames.SOLANA)?.let { PairRecords.decodeSolana(it) }.orEmpty()
        val algorand = session.getRecord(id, WalletRecordNames.ALGORAND)?.let { PairRecords.decodeAlgorand(it) }.orEmpty()
        PairedWallet(
            id = id,
            name = meta.name,
            fingerprint = meta.fingerprint,
            createdAtMs = meta.createdAtMs,
            bitcoin = bitcoin,
            evmAccounts = accounts,
            solana = solana,
            algorand = algorand,
        )
    } catch (e: IllegalArgumentException) {
        null
    }
}
