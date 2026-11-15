package app.lumio.btc

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lumio.data.cache.DEFAULT_SHORT_TTL_MS

/** One paired Bitcoin account and the wallet it came from. [key] names it in the cache and in the screen routes. */
data class BtcAccountRef(val walletId: String, val account: BtcWatchOnlyAccount) {
    val key: String get() = "${walletId}_${account.network.name}_${account.scriptType.name}"
}

/** One unspent output of an account. [blockHeight] is null while it is unconfirmed. */
data class BtcUtxo(val txid: String, val vout: Int, val valueSats: Long, val blockHeight: Long?)

/**
 * One of an account's recent transactions. [netSats] is what the transaction does to the account: positive when the account receives on
 * balance, negative when it sends. [blockHeight] is null while the transaction is unconfirmed.
 */
data class BtcTx(val txid: String, val netSats: Long, val blockHeight: Long?, val blockTimeSec: Long?)

/**
 * The result of the last scan of one account: its last known balance. The portfolio shows it at once, and the cache keeps it until a newer scan
 * replaces it. [scannedAtMs] dates it, and [tipHeight] is the best block the scan saw, which the confirmations are counted from. [identity] is the
 * account's first receive address, so a stored copy is used only for the same account.
 */
data class BtcSnapshot(
    val key: String,
    val identity: String,
    val scannedAtMs: Long,
    val tipHeight: Long,
    val confirmedSats: Long,
    val unconfirmedSats: Long,
    val nextReceiveIndex: Int,
    val nextReceiveAddress: String,
    val utxos: List<BtcUtxo>,
    val transactions: List<BtcTx>,
) {
    /** The confirmations of [tx] at the tip this scan saw. An unconfirmed transaction has none. */
    fun confirmations(tx: BtcTx): Long = tx.blockHeight?.let { maxOf(0L, tipHeight - it + 1) } ?: 0L
}

/**
 * True when a balance dated [scannedAtMs] is older than [ttlMs] at [nowMs]. A stale balance still shows, with a marker, until a scan replaces it.
 * The default TTL is the one the cache uses for live values.
 */
fun isStale(scannedAtMs: Long, nowMs: Long, ttlMs: Long = DEFAULT_SHORT_TTL_MS): Boolean = nowMs - scannedAtMs > ttlMs

/** One network's part of the portfolio. Its totals hold that network's accounts only: mainnet and testnet4 are never summed. */
data class BtcSection(val network: BtcNetwork, val rows: List<BtcRow>) {
    /** Confirmed satoshis of this network's scanned accounts. An account with no scan yet adds nothing. */
    val confirmedSats: Long get() = rows.sumOf { it.snapshot?.confirmedSats ?: 0L }

    /** Unconfirmed satoshis of this network's scanned accounts. */
    val unconfirmedSats: Long get() = rows.sumOf { it.snapshot?.unconfirmedSats ?: 0L }
}

/** One account of the portfolio, with its last known scan when it has one. */
data class BtcRow(val ref: BtcAccountRef, val snapshot: BtcSnapshot?)

/** The portfolio: one section per network that holds an account, mainnet first. There is no total across networks. */
object BtcPortfolio {
    /**
     * The rows of [refs], each with its last known scan when that scan is of the same account. A wallet that was deleted and paired again shows no
     * old numbers, because its first receive address differs.
     */
    fun rows(refs: List<BtcAccountRef>, cached: (String) -> BtcSnapshot?): List<BtcRow> =
        refs.map { ref -> BtcRow(ref, cached(ref.key)?.takeIf { it.identity == ref.account.receiveAddress(0) }) }

    /**
     * The notice a refresh shows for one network, or null when every account was read. When some accounts were read and others were not, the
     * notice names the accounts that were missed, and their rows keep their last known balance. [message] is shown alone when none was read.
     */
    fun refreshNotice(read: Int, missed: Int, message: String): String? = when {
        missed == 0 -> null
        read == 0 -> message
        else -> "$missed of ${read + missed} accounts could not be reached. Their last known balances are shown."
    }

    fun sections(rows: List<BtcRow>): List<BtcSection> = BtcNetwork.entries.mapNotNull { network ->
        rows.filter { it.ref.account.network == network }
            .sortedBy { it.ref.account.scriptType.ordinal }
            .takeIf { it.isNotEmpty() }
            ?.let { BtcSection(network, it) }
    }

    /** The page of [txid] on mempool.space, on the network's own path. */
    fun explorerTxUrl(network: BtcNetwork, txid: String): String = when (network) {
        BtcNetwork.MAINNET -> "https://mempool.space/tx/$txid"
        BtcNetwork.TESTNET4 -> "https://mempool.space/testnet4/tx/$txid"
    }
}
