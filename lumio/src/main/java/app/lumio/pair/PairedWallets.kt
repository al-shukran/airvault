package app.lumio.pair

import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.vault.VaultSession
import app.lensly.core.vault.WalletRecordNames
import app.lensly.core.wallet.AccountXpub

/** The chain families a paired wallet can hold. */
enum class PairFamily { EVM, BITCOIN }

/**
 * A paired wallet: one Lensly wallet, identified by its master fingerprint, with the public data of each family it was paired for. It holds
 * no key material. [evm] is the Ethereum account key, and [bitcoin] the watch-only Bitcoin Accounts. A family is held when its record is.
 */
data class PairedWallet(
    val id: String,
    val name: String,
    val fingerprint: Long,
    val createdAtMs: Long,
    val evm: AccountXpub?,
    val bitcoin: List<BtcWatchOnlyAccount>,
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
 * META holds the fingerprint, the creation time and the name, the account record holds the EVM account key, and the BITCOIN record holds the
 * watch-only Accounts. A pairing whose fingerprint is already paired updates that wallet, and never adds a second one.
 */
object PairedWallets {
    private const val DEFAULT_NAME = "Lensly"

    /** The paired wallets, oldest first. A folder that does not read as a pairing is left out. */
    fun list(session: VaultSession): List<PairedWallet> = session.listWallets()
        .mapNotNull { id -> read(session, id) }
        .sortedBy { it.createdAtMs }

    /**
     * Stores [pairing]. A new fingerprint makes a new paired wallet. A fingerprint that is already paired is updated: the family's record is
     * replaced, and the name follows the code when it carries one. The other family is kept.
     */
    fun save(session: VaultSession, pairing: Pairing, nowMs: Long): PairSaved {
        val paired = list(session).firstOrNull { it.fingerprint == pairing.fingerprint }
        if (paired == null) {
            val id = session.createWallet()
            try {
                session.putRecords(id, recordsFor(pairing, createdAtMs = nowMs, name = pairing.name ?: DEFAULT_NAME), emptyList())
            } catch (e: Throwable) {
                session.deleteWallet(id)
                throw e
            }
            return PairSaved(id, created = true)
        }
        session.putRecords(paired.id, recordsFor(pairing, createdAtMs = paired.createdAtMs, name = pairing.name ?: paired.name), emptyList())
        return PairSaved(paired.id, created = false)
    }

    /** Erases the paired wallet [id] and its records. The folder keeps its shape, as every wallet folder does. */
    fun delete(session: VaultSession, id: String) {
        session.deleteWallet(id)
    }

    /** The records that hold [pairing], written in one step. */
    private fun recordsFor(pairing: Pairing, createdAtMs: Long, name: String): Map<String, ByteArray> {
        val meta = PairRecords.encodeMeta(PairRecords.Meta(pairing.fingerprint, createdAtMs, name))
        return when (pairing) {
            is Pairing.Evm -> mapOf(WalletRecordNames.META to meta, WalletRecordNames.account(0) to PairRecords.encodeEvm(pairing.xpub))
            is Pairing.Bitcoin -> mapOf(
                WalletRecordNames.META to meta,
                WalletRecordNames.BITCOIN to PairRecords.encodeBitcoin(pairing.accounts),
            )
        }
    }

    /** The paired wallet in folder [id], or null when the folder is not one (another folder kind, or a damaged record). */
    private fun read(session: VaultSession, id: String): PairedWallet? = try {
        val meta = session.getRecord(id, WalletRecordNames.META)?.let { PairRecords.decodeMeta(it) } ?: return null
        val evm = session.getRecord(id, WalletRecordNames.account(0))?.let { PairRecords.decodeEvm(it) }
        val bitcoin = session.getRecord(id, WalletRecordNames.BITCOIN)
            ?.let { PairRecords.decodeBitcoin(it, meta.fingerprint) }
            .orEmpty()
        if (evm == null && bitcoin.isEmpty()) null else PairedWallet(id, meta.name, meta.fingerprint, meta.createdAtMs, evm, bitcoin)
    } catch (e: IllegalArgumentException) {
        null
    }
}
