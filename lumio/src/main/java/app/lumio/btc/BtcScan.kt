package app.lumio.btc

import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lumio.data.cache.CachePolicy
import app.lumio.data.cache.CacheUse
import app.lumio.data.cache.DEFAULT_SHORT_TTL_MS
import app.lumio.data.cache.jsonCodec
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.AddressStats
import app.lumio.data.provider.AddressTxs
import app.lumio.data.provider.AddressUtxos
import app.lumio.data.provider.TipHeight
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive

/** The network id of a Bitcoin network: "mainnet", or "testnet4" for the test network. */
fun BtcNetwork.networkId(): NetworkId =
    NetworkId(Family.BTC, if (this == BtcNetwork.MAINNET) "mainnet" else "testnet4", testnet = this == BtcNetwork.TESTNET4)

/** How many recent transactions an account keeps. */
const val RECENT_TX_COUNT = 20

/** A used address of a chain: its index on that chain, the address, and what the server says of it. */
internal class UsedAddress(val index: Int, val address: String, val stats: EsploraAddressStats)

/** The used addresses one chain turned up, in index order. */
internal class ChainScan(val used: List<UsedAddress>)

/**
 * The address statistics that this session has read, by network. A rescan asks again only for the addresses that need it (see [scanChain]).
 * It is kept in memory and never saved, so a new session starts with an empty one.
 */
class AddressStatsCache {
    private val byChain = ConcurrentHashMap<String, ConcurrentMap<String, MemoStats>>()

    internal fun forNetwork(net: NetworkId): ConcurrentMap<String, MemoStats> = byChain.getOrPut(net.chain) { ConcurrentHashMap() }
}

/** An address's statistics as a walk read them, and the wall-clock time of that read. An unused address is trusted only for [DEFAULT_SHORT_TTL_MS]. */
class MemoStats(val stats: EsploraAddressStats, val readAtMs: Long)

/**
 * Walks one chain of an account (receive or change) with the gap limit. It asks for each address in turn, and it stops once
 * [BtcAccount.GAP_LIMIT] addresses in a row have no history. A used address resets that count, so a gap shorter than the limit never
 * ends the walk.
 *
 * [memo] holds what an earlier walk of this session read. An address it found unused, and that lies before the last used address of that
 * walk, is not asked again while its read is younger than [DEFAULT_SHORT_TTL_MS] at [nowMs]. Past that, it is asked again: a payment to an
 * address below a used one must show within a minute, not only after a restart. The gap window after that address is asked again, since new
 * activity shows there first.
 */
internal suspend fun scanChain(
    addressAt: (Int) -> String,
    statsOf: suspend (String) -> EsploraAddressStats,
    memo: ConcurrentMap<String, MemoStats> = ConcurrentHashMap(),
    nowMs: Long = 0L,
): ChainScan {
    val used = mutableListOf<UsedAddress>()
    val lastUsed = lastUsedIn(addressAt, memo)
    var unused = 0
    var index = 0
    while (unused < BtcAccount.GAP_LIMIT) {
        coroutineContext.ensureActive()
        val address = addressAt(index)
        val fresh = memo[address]?.takeIf { !it.stats.used && index <= lastUsed && nowMs - it.readAtMs < DEFAULT_SHORT_TTL_MS }
        val stats = fresh?.stats ?: statsOf(address).also { memo[address] = MemoStats(it, nowMs) }
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
private fun lastUsedIn(addressAt: (Int) -> String, memo: Map<String, MemoStats>): Int {
    var last = -1
    var index = 0
    while (true) {
        val stats = memo[addressAt(index)] ?: return last
        if (stats.stats.used) last = index
        index++
    }
}

/**
 * Scans one account through [scheduler] at [priority]. The receive and change chains are walked at once with the gap limit, so they share the
 * requests that the scheduler allows. [step] reports how many addresses have been asked for so far. The balances are the sums over the used
 * addresses, with the confirmed and the unconfirmed side kept apart. An address with a balance is asked for its unspent outputs. The recent
 * transactions are asked for only when [detail] is set, for the account's own view, so a portfolio scan never asks for them.
 */
suspend fun scanAccount(
    ref: BtcAccountRef,
    scheduler: Scheduler,
    priority: Priority,
    nowMs: Long,
    memo: ConcurrentMap<String, MemoStats> = ConcurrentHashMap(),
    detail: Boolean = false,
    step: suspend (Int) -> Unit = {},
    onTxs: suspend (address: String, txs: List<EsploraTx>, tipHeight: Long) -> Unit = { _, _, _ -> },
): BtcSnapshot = coroutineScope {
    val account = ref.account
    val net = account.network.networkId()
    val asked = AtomicInteger(0)
    val statsOf: suspend (String) -> EsploraAddressStats = { address ->
        val stats = scheduler.run(
            AddressStats(net, address),
            priority,
            CacheUse(CachePolicy.ShortTtl(DEFAULT_SHORT_TTL_MS), "stats/${net.chain}/$address", jsonCodec()),
        )
        step(asked.incrementAndGet())
        stats
    }
    val tipHeight = scheduler.run(
        TipHeight(net),
        priority,
        CacheUse(CachePolicy.ShortTtl(DEFAULT_SHORT_TTL_MS), "tip/${net.chain}", jsonCodec()),
    )
    val (receiveWalk, changeWalk) = coroutineScope {
        val receive = async { scanChain(account::receiveAddress, statsOf, memo, nowMs) }
        val change = async { scanChain(account::changeAddress, statsOf, memo, nowMs) }
        receive.await() to change.await()
    }
    val used = receiveWalk.used + changeWalk.used
    val owned = used.map { it.address }.toSet()

    val recent: List<BtcTx> = if (!detail) emptyList() else {
        val txs = LinkedHashMap<String, EsploraTx>()
        for (address in owned) {
            val listed = scheduler.run(
                AddressTxs(net, address),
                priority,
                CacheUse(CachePolicy.ShortTtl(DEFAULT_SHORT_TTL_MS), "txs/${net.chain}/$address", jsonCodec()),
            )
            onTxs(address, listed, tipHeight)
            listed.forEach { txs.putIfAbsent(it.txid, it) }
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
            scheduler.run(
                AddressUtxos(net, hit.address),
                priority,
                CacheUse(CachePolicy.ShortTtl(DEFAULT_SHORT_TTL_MS), "utxo/${net.chain}/${hit.address}", jsonCodec()),
            ).map { BtcUtxo(it.txid, it.vout, it.valueSats, it.blockHeight) }
        }

    val usedReceive = receiveWalk.used.map { it.index }.toSet()
    val nextIndex = generateSequence(0) { it + 1 }.first { it !in usedReceive }
    BtcSnapshot(
        key = ref.key,
        identity = account.receiveAddress(0),
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
