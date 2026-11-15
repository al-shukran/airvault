package app.lumio.btc

import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** How many recent transactions an account keeps. */
const val RECENT_TX_COUNT = 20

/** A used address of a chain: its index on that chain, the address, and what the server says of it. */
internal class UsedAddress(val index: Int, val address: String, val stats: EsploraAddressStats)

/** The used addresses one chain turned up, in index order. */
internal class ChainScan(val used: List<UsedAddress>)

/**
 * The address statistics that one session has read, by server. A rescan asks again only for the addresses that need it (see [scanChain]).
 * It is kept in memory and never saved, so a new session starts with an empty one.
 */
class AddressStatsCache {
    private val byServer = ConcurrentHashMap<String, ConcurrentMap<String, EsploraAddressStats>>()

    internal fun forServer(server: String): ConcurrentMap<String, EsploraAddressStats> = byServer.getOrPut(server) { ConcurrentHashMap() }
}

/**
 * Walks one chain of an account (receive or change) with the gap limit. It asks for each address in turn, and it stops once
 * [BtcAccount.GAP_LIMIT] addresses in a row have no history. A used address resets that count, so a gap shorter than the limit never
 * ends the walk.
 *
 * [memo] holds what an earlier walk of this session read. An address it found unused, and that lies before the last used address of that
 * walk, is not asked again. The gap window after that address is asked again, since new activity shows there first.
 */
internal suspend fun scanChain(
    addressAt: (Int) -> String,
    statsOf: (String) -> EsploraAddressStats,
    memo: ConcurrentMap<String, EsploraAddressStats> = ConcurrentHashMap(),
): ChainScan {
    val used = mutableListOf<UsedAddress>()
    val lastUsed = lastUsedIn(addressAt, memo)
    var unused = 0
    var index = 0
    while (unused < BtcAccount.GAP_LIMIT) {
        coroutineContext.ensureActive()
        val address = addressAt(index)
        val stats = memo[address]?.takeIf { !it.used && index <= lastUsed } ?: statsOf(address).also { memo[address] = it }
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

/** The last index that [memo] holds as used, counting up from index 0 while [memo] holds the address; -1 when none is used. */
private fun lastUsedIn(addressAt: (Int) -> String, memo: Map<String, EsploraAddressStats>): Int {
    var last = -1
    var index = 0
    while (true) {
        val stats = memo[addressAt(index)] ?: return last
        if (stats.used) last = index
        index++
    }
}

/**
 * Scans one account over [client], which reads from [server]. The receive and change chains are walked with the gap limit, both at once,
 * so they share the two requests in flight that a host allows. The balances are the sums over the used addresses, with the confirmed and
 * the unconfirmed side kept apart. An address with a balance is asked for its unspent outputs. The recent transactions are asked for only
 * when [detail] is set, for the account's own view, so a portfolio scan never asks for them.
 */
suspend fun scanAccount(
    ref: BtcAccountRef,
    client: EsploraClient,
    server: String,
    nowMs: Long,
    memo: ConcurrentMap<String, EsploraAddressStats> = ConcurrentHashMap(),
    detail: Boolean = false,
): BtcSnapshot =
    withContext(Dispatchers.IO) {
        val account = ref.account
        val tipHeight = client.tipHeight()
        val (receiveWalk, changeWalk) = coroutineScope {
            val receive = async { scanChain(account::receiveAddress, client::addressStats, memo) }
            val change = async { scanChain(account::changeAddress, client::addressStats, memo) }
            receive.await() to change.await()
        }
        val used = receiveWalk.used + changeWalk.used
        val owned = used.map { it.address }.toSet()

        val recent: List<BtcTx> = if (!detail) emptyList() else {
            val txs = LinkedHashMap<String, EsploraTx>()
            for (address in owned) {
                ensureActive()
                client.addressTxs(address).forEach { txs.putIfAbsent(it.txid, it) }
            }
            txs.values
                .map { tx -> BtcTx(tx.txid, netSats(tx, owned), tx.blockHeight, tx.blockTimeSec) }
                .sortedWith(
                    compareByDescending<BtcTx> { it.blockHeight ?: Long.MAX_VALUE }
                        .thenByDescending { it.blockTimeSec ?: Long.MAX_VALUE }
                        .thenBy { it.txid },
                )
                .take(RECENT_TX_COUNT)
        }

        // An address with no balance holds no coins, so it is not asked for its unspent outputs.
        val utxos = used
            .filter { it.stats.balance != 0L }
            .flatMap { hit ->
                ensureActive()
                client.addressUtxos(hit.address).map { BtcUtxo(it.txid, it.vout, it.valueSats, it.blockHeight) }
            }

        val usedReceive = receiveWalk.used.map { it.index }.toSet()
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
 * Scans [refs] one after another and saves each result to [store]. [memo] is the session's address cache, and [detail] also reads the
 * recent transactions. [pause] does the waiting of a rate limit. When a network's server cannot be reached, or stays busy after its
 * retries, its other accounts are not tried, since each would wait on the same answer: the failure is recorded once, and those accounts
 * keep their cached scan. A portfolio scan keeps the transactions of the cached scan, since it does not read them.
 */
suspend fun scanAll(
    refs: List<BtcAccountRef>,
    servers: Map<BtcNetwork, String>,
    store: BtcStore,
    memo: AddressStatsCache = AddressStatsCache(),
    detail: Boolean = false,
    pause: (Long) -> Unit = { Thread.sleep(it) },
    nowMs: () -> Long,
): BtcScanReport {
    val fresh = LinkedHashMap<String, BtcSnapshot>()
    val failures = LinkedHashMap<BtcNetwork, String>()
    for (ref in refs) {
        val network = ref.account.network
        if (network in failures) continue
        val server = EsploraServers.baseUrl(network, servers)
        try {
            val scanned = scanAccount(ref, EsploraClient(server, pause), server, nowMs(), memo.forServer(server), detail)
            val snapshot = if (detail) scanned else scanned.copy(transactions = cachedTransactions(store, ref, server))
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

/** The transactions of the cached scan of [ref], when that scan is of the same account from the same server. */
private fun cachedTransactions(store: BtcStore, ref: BtcAccountRef, server: String): List<BtcTx> =
    store.snapshot(ref.key)?.takeIf { it.identity == ref.account.receiveAddress(0) && it.server == server }?.transactions.orEmpty()

/** The host name of [baseUrl], for a message the owner can read. */
internal fun hostOf(baseUrl: String): String = java.net.URI(baseUrl).host ?: baseUrl
