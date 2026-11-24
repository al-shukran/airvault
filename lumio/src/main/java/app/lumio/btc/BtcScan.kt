package app.lumio.btc

import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lumio.data.cache.CachePolicy
import app.lumio.data.cache.CacheUse
import app.lumio.data.cache.DEFAULT_SHORT_TTL_MS
import app.lumio.data.cache.jsonCodec
import app.lumio.data.electrum.ElectrumPool
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/** The network id of a Bitcoin network: "mainnet", or "testnet4" for the test network. */
fun BtcNetwork.networkId(): NetworkId =
    NetworkId(if (this == BtcNetwork.MAINNET) Networks.BTC_MAINNET else Networks.BTC_TESTNET4, testnet = this == BtcNetwork.TESTNET4)

/** How many recent transactions an account keeps. */
const val RECENT_TX_COUNT = 20

/** A used address of a chain: its index on that chain, the address, and what the scan read of it. */
internal class UsedAddress(val index: Int, val address: String, val reading: AddressReading)

/** What a scan read of one address: whether a transaction names it, and the coins it holds, confirmed and unconfirmed. */
internal class AddressReading(val used: Boolean, val confirmedSats: Long, val unconfirmedSats: Long) {
    val balance: Long get() = confirmedSats + unconfirmedSats
}

/** What Esplora says of an address, as a scan reads it. */
internal fun EsploraAddressStats.reading(): AddressReading = AddressReading(used, chain.net, mempool.net)

/** The used addresses one chain turned up, in index order. */
internal class ChainScan(val used: List<UsedAddress>)

/**
 * The address statistics that this session has read, by network. A rescan asks again only for the addresses that need it (see [scanChain]).
 * It is kept in memory and never saved, so a new session starts with an empty one.
 */
class AddressStatsCache {
    private val byChain = ConcurrentHashMap<String, ConcurrentMap<String, MemoStats>>()

    internal fun forNetwork(net: NetworkId): ConcurrentMap<String, MemoStats> = byChain.getOrPut(net.name) { ConcurrentHashMap() }
}

/** An address's statistics as a walk read them, and the wall-clock time of that read. An unused address is trusted only for [DEFAULT_SHORT_TTL_MS]. */
class MemoStats(val stats: EsploraAddressStats, val readAtMs: Long)

/**
 * Walks one chain of an account (receive or change) with the gap limit. It asks for the addresses in windows of [window], every address of a
 * window at once, and it stops once [BtcAccount.GAP_LIMIT] addresses in a row have no history. A used address resets that count, so a gap
 * shorter than the limit never ends the walk. The walk finds the same used addresses for any window: a window is asked for only while the walk
 * has not stopped, and the reads past the stop are cancelled. A window of 1 asks for one address at a time, so it never reads past the stop.
 *
 * [memo] holds what an earlier walk of this session read. An address it found unused, and that lies before the last used address of that
 * walk, is not asked again while its read is younger than [DEFAULT_SHORT_TTL_MS] at [nowMs]. Past that, it is asked again: a payment to an
 * address below a used one must show within a minute, not only after a restart. The gap window after that address is asked again, since new
 * activity shows there first. With [trustWithinTtl], every address read within the TTL is trusted, used or not, so a walk that follows a scan
 * asks the server for none of them. [found] gets each used address as the walk finds it, so a walk that fails part way keeps those addresses.
 */
internal suspend fun scanChain(
    addressAt: (Int) -> String,
    statsOf: suspend (String) -> EsploraAddressStats,
    memo: ConcurrentMap<String, MemoStats> = ConcurrentHashMap(),
    nowMs: Long = 0L,
    trustWithinTtl: Boolean = false,
    found: MutableList<UsedAddress> = mutableListOf(),
    window: Int = BtcAccount.GAP_LIMIT,
): ChainScan = supervisorScope {
    val lastUsed = lastUsedIn(addressAt, memo)
    // The reads started so far, by index. A failed read fails only the walk that awaits it, so the used addresses before it are kept.
    val reads = ArrayList<Deferred<EsploraAddressStats>>()
    fun read(index: Int): Deferred<EsploraAddressStats> = async {
        val address = addressAt(index)
        val fresh = memo[address]?.takeIf {
            nowMs - it.readAtMs < DEFAULT_SHORT_TTL_MS && (trustWithinTtl || (!it.stats.used && index <= lastUsed))
        }
        fresh?.stats ?: statsOf(address).also { memo[address] = MemoStats(it, nowMs) }
    }
    var unused = 0
    var index = 0
    try {
        while (unused < BtcAccount.GAP_LIMIT) {
            coroutineContext.ensureActive()
            // Every address before this one has been walked and the gap is still open, so the next window is asked for at once.
            if (index == reads.size) repeat(window) { reads += read(reads.size) }
            val stats = reads[index].await()
            if (stats.used) {
                found += UsedAddress(index, addressAt(index), stats.reading())
                unused = 0
            } else {
                unused++
            }
            index++
        }
    } finally {
        // The reads past the stop are not needed, so they are cancelled rather than waited for.
        reads.forEach { it.cancel() }
    }
    ChainScan(found)
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
 *
 * With [electrum], the walk and the unspent outputs come from Electrum in batches, and the transactions still come from Esplora. An account that
 * Electrum fails to read is read from Esplora instead.
 *
 * A request that fails part way ends the scan with its error. First [onPartial] gets the snapshot of what was read up to then, and the caller keeps
 * it, so the next scan of the account starts from that balance rather than from nothing.
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
    onPartial: suspend (BtcSnapshot) -> Unit = {},
    electrum: ElectrumPool? = null,
): BtcSnapshot {
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
    // What the scan has read so far. A request that fails leaves it here, and [snapshot] keeps it.
    val receiveUsed = mutableListOf<UsedAddress>()
    val changeUsed = mutableListOf<UsedAddress>()
    val txs = LinkedHashMap<String, EsploraTx>()
    val utxos = mutableListOf<BtcUtxo>()
    fun snapshot(): BtcSnapshot {
        val used = receiveUsed + changeUsed
        val owned = used.map { it.address }.toSet()
        val usedReceive = receiveUsed.map { it.index }.toSet()
        val nextIndex = generateSequence(0) { it + 1 }.first { it !in usedReceive }
        return BtcSnapshot(
            key = ref.key,
            identity = account.receiveAddress(0),
            scannedAtMs = nowMs,
            tipHeight = tipHeight,
            confirmedSats = used.sumOf { it.reading.confirmedSats },
            unconfirmedSats = used.sumOf { it.reading.unconfirmedSats },
            nextReceiveIndex = nextIndex,
            nextReceiveAddress = account.receiveAddress(nextIndex),
            utxos = utxos.toList(),
            transactions = if (!detail) emptyList() else txs.values
                .map { tx -> BtcTx(tx.txid, netSats(tx, owned), tx.blockHeight, tx.blockTimeSec) }
                .sortedWith(
                    compareByDescending<BtcTx> { it.blockHeight ?: Long.MAX_VALUE }
                        .thenByDescending { it.blockTimeSec ?: Long.MAX_VALUE }
                        .thenBy { it.txid },
                )
                .take(RECENT_TX_COUNT),
            addresses = used.map { it.address },
        )
    }
    // Electrum reads the walk and the unspent outputs when it can. An account that it fails to read is read from Esplora below.
    val fromElectrum = electrum?.let { electrumRead(it, account, step) }
    try {
        if (fromElectrum != null) {
            receiveUsed += fromElectrum.receive
            changeUsed += fromElectrum.change
            utxos += fromElectrum.utxos
        } else {
            coroutineScope {
                launch { scanChain(account::receiveAddress, statsOf, memo, nowMs, found = receiveUsed) }
                launch { scanChain(account::changeAddress, statsOf, memo, nowMs, found = changeUsed) }
            }
            // An address with no balance holds no coins, so it is not asked for its unspent outputs.
            for (hit in (receiveUsed + changeUsed).filter { it.reading.balance != 0L }) {
                utxos += scheduler.run(
                    AddressUtxos(net, hit.address),
                    priority,
                    CacheUse(CachePolicy.ShortTtl(DEFAULT_SHORT_TTL_MS), "utxo/${net.chain}/${hit.address}", jsonCodec()),
                ).map { BtcUtxo(it.txid, it.vout, it.valueSats, it.blockHeight) }
            }
        }
        if (detail) {
            for (address in (receiveUsed + changeUsed).map { it.address }) {
                val listed = scheduler.run(
                    AddressTxs(net, address),
                    priority,
                    CacheUse(CachePolicy.ShortTtl(DEFAULT_SHORT_TTL_MS), "txs/${net.chain}/$address", jsonCodec()),
                )
                onTxs(address, listed, tipHeight)
                listed.forEach { txs.putIfAbsent(it.txid, it) }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onPartial(snapshot())
        throw e
    }
    return snapshot()
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
