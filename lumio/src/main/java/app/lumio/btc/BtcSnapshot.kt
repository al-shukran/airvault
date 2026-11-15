package app.lumio.btc

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnlyAccount

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
 * The result of the last scan of one account. The portfolio shows it, and the vault caches it. [scannedAtMs] dates it, and [tipHeight] is the
 * best block the scan saw, which the confirmations are counted from. [server] is the base URL it came from, and [identity] is the account's
 * first receive address, so a cached copy is used only for the same account from the same server.
 */
data class BtcSnapshot(
    val key: String,
    val identity: String,
    val server: String,
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

/** One network's part of the portfolio. Its totals hold that network's accounts only: mainnet and testnet4 are never summed. */
data class BtcSection(val network: BtcNetwork, val rows: List<BtcRow>) {
    /** Confirmed satoshis of this network's scanned accounts. An account with no scan yet adds nothing. */
    val confirmedSats: Long get() = rows.sumOf { it.snapshot?.confirmedSats ?: 0L }

    /** Unconfirmed satoshis of this network's scanned accounts. */
    val unconfirmedSats: Long get() = rows.sumOf { it.snapshot?.unconfirmedSats ?: 0L }
}

/** One account of the portfolio, with its last scan when it has one. */
data class BtcRow(val ref: BtcAccountRef, val snapshot: BtcSnapshot?)

/** The portfolio: one section per network that holds an account, mainnet first. There is no total across networks. */
object BtcPortfolio {
    /**
     * The rows of [refs], each with its cached scan when that scan still applies. A cached scan applies only to the same account, and only
     * when it came from the server that the account's network reads from now. So a changed server, or a wallet that was deleted and paired
     * again, shows no old numbers.
     */
    fun rows(refs: List<BtcAccountRef>, cached: (String) -> BtcSnapshot?, servers: Map<BtcNetwork, String>): List<BtcRow> =
        refs.map { ref ->
            val server = EsploraServers.baseUrl(ref.account.network, servers)
            val snapshot = cached(ref.key)?.takeIf { it.identity == ref.account.receiveAddress(0) && it.server == server }
            BtcRow(ref, snapshot)
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
