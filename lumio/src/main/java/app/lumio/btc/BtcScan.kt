package app.lumio.btc

import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** How many recent transactions an account keeps. */
const val RECENT_TX_COUNT = 20

/** A used address of a chain: its index on that chain, the address, and what the server says of it. */
internal class UsedAddress(val index: Int, val address: String, val stats: EsploraAddressStats)

/** The used addresses one chain turned up, in index order. */
internal class ChainScan(val used: List<UsedAddress>)

/**
 * Walks one chain of an account (receive or change) with the gap limit. It asks for each address in turn, and it stops once
 * [BtcAccount.GAP_LIMIT] addresses in a row have no history. A used address resets that count, so a gap shorter than the limit never
 * ends the walk.
 */
internal suspend fun scanChain(addressAt: (Int) -> String, statsOf: (String) -> EsploraAddressStats): ChainScan {
    val used = mutableListOf<UsedAddress>()
    var unused = 0
    var index = 0
    while (unused < BtcAccount.GAP_LIMIT) {
        coroutineContext.ensureActive()
        val address = addressAt(index)
        val stats = statsOf(address)
        if (stats.used) {
            used += UsedAddress(index, address, stats)
            unused = 0
        } else {
            unused++
        }
        index++
    }
    return ChainScan(used)
}

/**
 * Scans one account over [client], which reads from [server]. The receive and change chains are walked with the gap limit. Each used
 * address is then asked for its recent transactions and, when it may hold coins, for its unspent outputs. The balances are the sums over
 * the used addresses, with the confirmed and the unconfirmed side kept apart.
 */
suspend fun scanAccount(ref: BtcAccountRef, client: EsploraClient, server: String, nowMs: Long): BtcSnapshot =
    withContext(Dispatchers.IO) {
        val account = ref.account
        val tipHeight = client.tipHeight()
        val receive = scanChain(account::receiveAddress, client::addressStats)
        val change = scanChain(account::changeAddress, client::addressStats)
        val used = receive.used + change.used
        val owned = used.map { it.address }.toSet()

        val txs = LinkedHashMap<String, EsploraTx>()
        for (address in owned) {
            ensureActive()
            client.addressTxs(address).forEach { txs.putIfAbsent(it.txid, it) }
        }
        val recent = txs.values
            .map { tx -> BtcTx(tx.txid, netSats(tx, owned), tx.blockHeight, tx.blockTimeSec) }
            .sortedWith(
                compareByDescending<BtcTx> { it.blockHeight ?: Long.MAX_VALUE }
                    .thenByDescending { it.blockTimeSec ?: Long.MAX_VALUE }
                    .thenBy { it.txid },
            )
            .take(RECENT_TX_COUNT)

        // An address with no funded output left, and none in the mempool, holds no coins, so it is not asked.
        val utxos = used
            .filter { it.stats.chain.fundedCount > it.stats.chain.spentCount || it.stats.mempool.fundedCount > 0 }
            .flatMap { hit ->
                ensureActive()
                client.addressUtxos(hit.address).map { BtcUtxo(it.txid, it.vout, it.valueSats, it.blockHeight) }
            }

        val usedReceive = receive.used.map { it.index }.toSet()
        val nextIndex = generateSequence(0) { it + 1 }.first { it !in usedReceive }
        BtcSnapshot(
            key = ref.key,
            identity = account.receiveAddress(0),
            server = server,
            scannedAtMs = nowMs,
            tipHeight = tipHeight,
            confirmedSats = used.sumOf { it.stats.chain.net },
            unconfirmedSats = used.sumOf { it.stats.mempool.net },
            nextReceiveIndex = nextIndex,
            nextReceiveAddress = account.receiveAddress(nextIndex),
            utxos = utxos,
            transactions = recent,
        )
    }

/**
 * What [tx] does to an account that owns the addresses in [owned]: the value of the outputs paid to those addresses, minus the value of
 * the previous outputs the account spends. A positive net means the account receives on balance, a negative one that it sends. The fee is
 * paid by the spending side, so it shows only as the gap between what is spent and what comes back as change.
 */
internal fun netSats(tx: EsploraTx, owned: Set<String>): Long {
    val received = tx.outputs.filter { it.address.isIn(owned) }.sumOf { it.valueSats }
    val spent = tx.inputs.mapNotNull { it.prevout }.filter { it.address.isIn(owned) }.sumOf { it.valueSats }
    return received - spent
}

private fun String?.isIn(owned: Set<String>): Boolean = this != null && this in owned

/** What one refresh of the portfolio found: the accounts it scanned now, and a failure message for each network it could not reach. */
data class BtcScanReport(val fresh: Map<String, BtcSnapshot>, val failures: Map<BtcNetwork, String>)

/**
 * Scans [refs] one after another and saves each result to [store]. When a network's server cannot be reached, its other accounts are
 * not tried, since each would wait on the same timeout: the failure is recorded once, and those accounts keep their cached scan.
 */
suspend fun scanAll(
    refs: List<BtcAccountRef>,
    servers: Map<BtcNetwork, String>,
    store: BtcStore,
    nowMs: () -> Long,
): BtcScanReport {
    val fresh = LinkedHashMap<String, BtcSnapshot>()
    val failures = LinkedHashMap<BtcNetwork, String>()
    for (ref in refs) {
        val network = ref.account.network
        if (network in failures) continue
        val server = EsploraServers.baseUrl(network, servers)
        try {
            val snapshot = scanAccount(ref, EsploraClient(server), server, nowMs())
            store.save(snapshot)
            fresh[ref.key] = snapshot
        } catch (e: IOException) {
            failures[network] = if (e is EsploraException) {
                e.message ?: "The server's answer could not be read."
            } else {
                "Could not reach ${hostOf(server)}."
            }
        }
    }
    return BtcScanReport(fresh, failures)
}

/** The host name of [baseUrl], for a message the owner can read. */
internal fun hostOf(baseUrl: String): String = java.net.URI(baseUrl).host ?: baseUrl
