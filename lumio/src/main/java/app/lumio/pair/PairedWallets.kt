package app.lumio.pair

import app.lensly.app.AddressBookEntry
import app.lensly.app.InternalAccount
import app.lensly.app.addressKeyOf
import app.lensly.app.autoAccountName
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingPayload
import app.lensly.app.plainName
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultSession
import app.lensly.core.vault.WalletRecordNames
import app.lumio.data.net.Networks

/** The chain families a paired wallet can hold. */
enum class PairChain { EVM, BITCOIN, SOLANA, ALGORAND }

/**
 * One Ethereum account of a paired wallet, as Lensly names it: its number N, its [address] (EIP-55, the address of m/44'/60'/N'/0/0), and its
 * name, label and group.
 */
data class PairedAccount(val index: Int, val address: String, val name: String?, val label: String?, val group: String?)

/**
 * A paired wallet: one Lensly wallet, identified by its master fingerprint, with the public data of its Apps. It holds no key material.
 * [evmAccounts] are the Ethereum accounts, each with its address. [bitcoin] holds the watch-only Bitcoin Accounts, if any.
 * [addressBook] is the Address Book that Lensly sent with the pairing, in the order it was sent. It is empty when there is none.
 * [solana] holds the public key of each Solana account, 32 bytes, in account order. Lumio shows each one's base58 address.
 * [algorand] holds the public key of each Algorand account, 32 bytes, in account order. Lumio shows each one's address.
 * [bitcoinNames], [solanaNames] and [algorandNames] are the names Lensly's owner gave those accounts, one per account in account order, and null
 * for an account that has its Auto-Name, which [bitcoinNameAt], [solanaNameAt] and [algorandNameAt] build from [name] and the account index. They are empty only for a
 * wallet built without them. Ethereum accounts carry their names in [evmAccounts].
 * [bookRank] is how recently the wallet's Address Book was paired: the higher, the later. A wallet with no book has -1.
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
    val bitcoinNames: List<String?> = emptyList(),
    val solanaNames: List<String?> = emptyList(),
    val algorandNames: List<String?> = emptyList(),
    val bookRank: Int = -1,
) {
    /** The address of each Ethereum account, in account order. Empty when the wallet holds no Ethereum account. */
    val evmAddresses: List<String>
        get() = evmAccounts.map { it.address }

    val families: Set<PairChain>
        get() = buildSet {
            if (evmAccounts.isNotEmpty()) add(PairChain.EVM)
            if (bitcoin.isNotEmpty()) add(PairChain.BITCOIN)
            if (solana.isNotEmpty()) add(PairChain.SOLANA)
            if (algorand.isNotEmpty()) add(PairChain.ALGORAND)
        }

    /** The name that the Bitcoin account at [index] shows: its custom name or its Auto-Name. Null for a wallet built without names. */
    fun bitcoinNameAt(index: Int): String? = bitcoinNames.getOrElse(index) { return null } ?: bitcoinAutoNameAt(index)

    /** The Auto-Name of the Bitcoin account at [index]: [name] and the account number in its path. */
    fun bitcoinAutoNameAt(index: Int): String = autoAccountName(name, (bitcoin[index].path.path[ACCOUNT_STEP] and INDEX_MASK).toInt())

    /** The name that the Solana account at [index] shows: its custom name or its Auto-Name. Null for a wallet built without names. */
    fun solanaNameAt(index: Int): String? = solanaNames.getOrElse(index) { return null } ?: autoAccountName(name, index)

    /** The name that the Algorand account at [index] shows: its custom name or its Auto-Name. Null for a wallet built without names. */
    fun algorandNameAt(index: Int): String? = algorandNames.getOrElse(index) { return null } ?: autoAccountName(name, index)

    private companion object {
        const val ACCOUNT_STEP = 2
        const val INDEX_MASK = 0x7FFF_FFFFL
    }
}

/**
 * The Address Book that the Address Book page of Settings shows and its row counts: the books of [wallets] as one, in the order an entry was first seen.
 * An entry that wallets share, which has the same id, shows once, as the copy of the wallet whose book was paired last (a re-pair counts), so a rename that a re-pair brought is kept.
 */
fun sharedAddressBook(wallets: List<PairedWallet>): List<AddressBookEntry> =
    wallets.sortedBy { it.bookRank }.flatMap { it.addressBook }.associateBy { it.id }.values.toList()

/**
 * The accounts of [wallets], each keyed by [addressKeyOf] its address, with the name and label the pairing sent for it: an Ethereum, Solana or
 * Algorand account by its address, and a Bitcoin account by its first receive address, the address the account list shows. An account with no
 * name keeps its Auto-Name, as Lensly names it.
 */
fun ownAccountsOf(wallets: List<PairedWallet>): Map<String, InternalAccount> = buildMap {
    wallets.forEach { wallet ->
        wallet.evmAccounts.forEach { account ->
            val autoName = autoAccountName(wallet.name, account.index)
            put(addressKeyOf(account.address), InternalAccount(displayName = account.name, autoName = autoName, label = account.label))
        }
        wallet.bitcoin.forEachIndexed { index, account ->
            put(addressKeyOf(account.receiveAddress(0)), InternalAccount(wallet.bitcoinNames.getOrNull(index), wallet.bitcoinAutoNameAt(index), null))
        }
        wallet.solana.forEachIndexed { index, key ->
            put(addressKeyOf(SolanaAccount.addressOf(key)), InternalAccount(wallet.solanaNames.getOrNull(index), autoAccountName(wallet.name, index), null))
        }
        wallet.algorand.forEachIndexed { index, key ->
            put(addressKeyOf(AlgorandAccount.addressOf(key)), InternalAccount(wallet.algorandNames.getOrNull(index), autoAccountName(wallet.name, index), null))
        }
    }
}

/** The name each account shows as plain text. An account whose names have nothing visible left is left out, so a screen falls back to the address. */
fun Map<String, InternalAccount>.plainNames(): Map<String, String> = mapNotNull { (key, account) -> account.plainName()?.let { key to it } }.toMap()

/** The result of a save: the wallet that now holds the pairing, and whether this save created it. False means an update. */
data class PairSaved(val id: String, val created: Boolean)

/**
 * The paired wallets of an open Lumio vault. Each is one wallet folder of the vault, so its records are sealed with the wallet's own key:
 * META holds the fingerprint, the creation time, the name, the address of each Ethereum account and the name of each other account, each Ethereum
 * account's record holds its name, label and group, and the BITCOIN record holds the watch-only Accounts. A pairing whose fingerprint is already paired replaces that wallet's
 * records, and never adds a second one.
 */
object PairedWallets {

    /**
     * The paired wallets, oldest first, each with its Address Book. A folder with no META record is left out, and so is one whose pairing record
     * does not read in full, which is kept as it is (see [readOrNull]). A book record that does not read is wiped, and the pairings are kept.
     */
    fun list(session: VaultSession): List<PairedWallet> {
        val books = try {
            PairAddressBooks.load(session)
        } catch (e: VaultException) {
            PairAddressBooks.clear(session)
            emptyMap()
        }
        // The wallets are read once per write: each read costs a Keystore call, and every screen asks for them.
        val ids = session.listWallets()
        val writes = session.writeCount
        val (cached, forgotten) = synchronized(kept) { kept[session] to forgets }
        val wallets = cached?.takeIf { it.writes == writes && it.ids == ids }?.wallets
            ?: ids.mapNotNull { id -> readOrNull(session, id) }.also {
                // A forget since this read began means the session was locked: keep nothing, or a lock would not drop it.
                synchronized(kept) { if (forgets == forgotten) kept[session] = Kept(writes, ids, it) }
            }
        val rank = books.keys.withIndex().associate { it.value to it.index }
        return wallets.map { it.copy(addressBook = books[it.id].orEmpty(), bookRank = rank[it.id] ?: -1) }.sortedBy { it.createdAtMs }
    }

    /** The wallets read at a write count, for the session that read them. A closed session drops out with its key. */
    internal class Kept(val writes: Long, val ids: List<String>, val wallets: List<PairedWallet>)

    /** Internal for tests only: production code must not touch it outside [list] and [forget]. */
    internal val kept = java.util.WeakHashMap<VaultSession, Kept>()

    /** How many times [forget] ran, guarded by [kept]. A [list] that began before a forget does not store what it read. */
    private var forgets = 0L

    /** Drops the wallets kept for [session], so no read of them outlives a lock. [LumioVault.wipe] calls it. */
    fun forget(session: VaultSession) {
        synchronized(kept) {
            forgets++
            kept.remove(session)
        }
    }

    internal fun isKept(session: VaultSession): Boolean = synchronized(kept) { kept.containsKey(session) }

    /**
     * The paired wallet in folder [id], or null when the folder is not paired. It reads that folder only, where [list] reads every folder. Its
     * [PairedWallet.addressBook] is empty: [list] is the read that holds the Address Book.
     */
    fun get(session: VaultSession, id: String): PairedWallet? = readOrNull(session, id)

    /**
     * Stores [pairing], as Lensly exported it. Lensly is the source of truth: the names, labels and groups, the accounts, the keys and the
     * Address Book replace what Lumio held for the same fingerprint, so an account or an entry that Lensly no longer lists is removed. A new
     * fingerprint makes a new wallet.
     */
    fun save(session: VaultSession, pairing: PairingPayload, nowMs: Long): PairSaved {
        val paired = list(session).firstOrNull { it.fingerprint == pairing.fingerprint }?.let { it.id to it.createdAtMs }
            ?: unreadableWith(session, pairing.fingerprint)
        val id = paired?.first ?: session.createWallet()
        try {
            session.putRecords(id, recordsFor(pairing, createdAtMs = paired?.second ?: nowMs), deletesFor(pairing))
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
        val meta = PairRecords.Meta(
            fingerprint = pairing.fingerprint,
            createdAtMs = createdAtMs,
            name = pairing.name,
            addresses = PairImport.ethereumAddressesOf(pairing),
            bitcoinNames = PairImport.customNamesOf(pairing, Networks.BIP122),
            solanaNames = PairImport.customNamesOf(pairing, Networks.SOLANA),
            algorandNames = PairImport.customNamesOf(pairing, Networks.ALGORAND),
        )
        val records = mutableMapOf(WalletRecordNames.META to PairRecords.encodeMeta(meta))
        for ((index, account) in PairImport.accountsOf(pairing, Networks.EIP155).withIndex()) {
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
        val count = PairImport.accountsOf(pairing, Networks.EIP155).size
        val accounts = (count until PairingCodec.MAX_ETHEREUM_ACCOUNTS).map { WalletRecordNames.account(it) }
        val bitcoin = if (PairImport.bitcoinAccountsOf(pairing).isEmpty()) listOf(WalletRecordNames.BITCOIN) else emptyList()
        val solana = if (PairImport.solanaPublicKeysOf(pairing).isEmpty()) listOf(WalletRecordNames.SOLANA) else emptyList()
        val algorand = if (PairImport.algorandPublicKeysOf(pairing).isEmpty()) listOf(WalletRecordNames.ALGORAND) else emptyList()
        return accounts + bitcoin + solana + algorand
    }

    /**
     * The paired wallet in folder [id], or null when its record does not read in full. The folder is kept as it is. A record that
     * authenticates but does not read belongs to another format, and reading or pairing another wallet must not remove it. Such a folder
     * reads as unpaired. A pairing with its fingerprint replaces it when its META record reads (see [unreadableWith]).
     */
    private fun readOrNull(session: VaultSession, id: String): PairedWallet? = try {
        read(session, id)
    } catch (e: IllegalArgumentException) {
        null
    }

    /**
     * The folder, and its creation time, of a kept wallet whose META record reads and holds [fingerprint] while another of its records does not. A
     * pairing of that fingerprint replaces its records, so it never adds a second wallet beside it.
     */
    private fun unreadableWith(session: VaultSession, fingerprint: Long): Pair<String, Long>? = session.listWallets().firstNotNullOfOrNull { id ->
        val meta = try {
            session.getRecord(id, WalletRecordNames.META)?.let { PairRecords.decodeMeta(it) }
        } catch (e: IllegalArgumentException) {
            null
        }
        meta?.takeIf { it.fingerprint == fingerprint }?.let { id to it.createdAtMs }
    }

    /** The paired wallet in folder [id], or null when the folder has no META record. A record that does not read in full throws. */
    private fun read(session: VaultSession, id: String): PairedWallet? {
        // One call reads every record of the folder, so the wallet's key is unwrapped once, not once per record.
        val records = session.getRecords(
            id,
            listOf(WalletRecordNames.META, WalletRecordNames.BITCOIN, WalletRecordNames.SOLANA, WalletRecordNames.ALGORAND) +
                (0 until PairingCodec.MAX_ETHEREUM_ACCOUNTS).map { WalletRecordNames.account(it) },
        )
        val meta = records[WalletRecordNames.META]?.let { PairRecords.decodeMeta(it) } ?: return null
        val accounts = meta.addresses.mapIndexed { index, address ->
            val account = PairRecords.decodeAccount(records[WalletRecordNames.account(index)] ?: return null)
            PairedAccount(index, address, account.name, account.label, account.group)
        }
        val bitcoin = records[WalletRecordNames.BITCOIN]
            ?.let { PairRecords.decodeBitcoin(it, meta.fingerprint) }
            .orEmpty()
        val solana = records[WalletRecordNames.SOLANA]?.let { PairRecords.decodeSolana(it) }.orEmpty()
        val algorand = records[WalletRecordNames.ALGORAND]?.let { PairRecords.decodeAlgorand(it) }.orEmpty()
        require(meta.bitcoinNames.size == bitcoin.size && meta.solanaNames.size == solana.size && meta.algorandNames.size == algorand.size) {
            "A name is missing from the wallet record."
        }
        return PairedWallet(
            id = id,
            name = meta.name,
            fingerprint = meta.fingerprint,
            createdAtMs = meta.createdAtMs,
            bitcoin = bitcoin,
            evmAccounts = accounts,
            solana = solana,
            algorand = algorand,
            bitcoinNames = meta.bitcoinNames,
            solanaNames = meta.solanaNames,
            algorandNames = meta.algorandNames,
        )
    }
}
