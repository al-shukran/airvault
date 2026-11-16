package app.lumio.pair

import app.lensly.app.AddressBookEntry
import app.lensly.app.export.PairingPayload
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultSession
import app.lensly.core.vault.WalletRecordNames
import app.lensly.core.wallet.AccountXpub

/** The chain families a paired wallet can hold. */
enum class PairFamily { EVM, BITCOIN }

/** One Ethereum account of a paired wallet, as Lensly names it: its address index under the account key, and its name, label and group. */
data class PairedAccount(val index: Int, val name: String?, val label: String?, val group: String?)

/**
 * A paired wallet: one Lensly wallet, identified by its master fingerprint, with the public data of its Apps. It holds no key material. [evm]
 * is the Ethereum account key and [evmAccounts] the Ethereum accounts under it. [bitcoin] holds the watch-only Bitcoin Accounts, if any.
 * [addressBook] is the Address Book that Lensly sent with the pairing, in the order it was sent. It is empty when there is none.
 */
data class PairedWallet(
    val id: String,
    val name: String,
    val fingerprint: Long,
    val createdAtMs: Long,
    val evm: AccountXpub?,
    val bitcoin: List<BtcWatchOnlyAccount>,
    val evmAccounts: List<PairedAccount> = emptyList(),
    val addressBook: List<AddressBookEntry> = emptyList(),
) {
    val families: Set<PairFamily>
        get() = buildSet {
            if (evm != null) add(PairFamily.EVM)
            if (bitcoin.isNotEmpty()) add(PairFamily.BITCOIN)
        }
}

/** The result of a save: the wallet that now holds the pairing, and whether this save created it. False means an update. */
data class PairSaved(val id: String, val created: Boolean)

/**
 * The paired wallets of an open Lumio vault. Each is one wallet folder of the vault, so its records are sealed with the wallet's own key:
 * META holds the fingerprint, the creation time, the name, the Ethereum account key and the number of its accounts, each account's record
 * holds its name, label and group, and the BITCOIN record holds the watch-only Accounts. A pairing whose fingerprint is already paired
 * replaces that wallet's records, and never adds a second one.
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
            accountCount = ethereum.accounts.size,
            evm = PairImport.ethereumKeyOf(pairing),
        )
        val records = mutableMapOf(WalletRecordNames.META to PairRecords.encodeMeta(meta))
        for (account in ethereum.accounts) {
            val index = requireNotNull(account.child) { "A validated Ethereum account has an index." }
            records[WalletRecordNames.account(index)] = PairRecords.encodeAccount(
                PairRecords.Account(name = account.name, label = account.label, group = account.group),
            )
        }
        val bitcoin = PairImport.bitcoinAccountsOf(pairing)
        if (bitcoin.isNotEmpty()) records[WalletRecordNames.BITCOIN] = PairRecords.encodeBitcoin(bitcoin)
        return records
    }

    /** The records of [pairing] that a wallet holding it must not keep: the account records past its last account, and an empty Bitcoin record. */
    private fun deletesFor(pairing: PairingPayload): List<String> {
        val count = pairing.apps.first { it.app == PairImport.ETHEREUM }.accounts.size
        val accounts = (count until PairImport.MAX_ETHEREUM_ACCOUNTS).map { WalletRecordNames.account(it) }
        return if (PairImport.bitcoinAccountsOf(pairing).isEmpty()) accounts + WalletRecordNames.BITCOIN else accounts
    }

    /** The paired wallet in folder [id], or null when the folder is not one (another folder kind, or a damaged record). */
    private fun read(session: VaultSession, id: String): PairedWallet? = try {
        val meta = session.getRecord(id, WalletRecordNames.META)?.let { PairRecords.decodeMeta(it) } ?: return null
        val accounts = (0 until meta.accountCount).map { index ->
            val account = PairRecords.decodeAccount(session.getRecord(id, WalletRecordNames.account(index)) ?: return null)
            PairedAccount(index, account.name, account.label, account.group)
        }
        val bitcoin = session.getRecord(id, WalletRecordNames.BITCOIN)
            ?.let { PairRecords.decodeBitcoin(it, meta.fingerprint) }
            .orEmpty()
        PairedWallet(
            id = id,
            name = meta.name,
            fingerprint = meta.fingerprint,
            createdAtMs = meta.createdAtMs,
            evm = meta.evm,
            bitcoin = bitcoin,
            evmAccounts = accounts,
        )
    } catch (e: IllegalArgumentException) {
        null
    }
}
