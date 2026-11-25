package app.lumio.btc

import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lumio.data.electrum.ElectrumPool
import app.lumio.data.electrum.scripthash
import app.lumio.data.electrum.scripthashBalances
import app.lumio.data.electrum.scripthashHistories
import app.lumio.data.electrum.scripthashUnspent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The pool that reads the Bitcoin of [network] from the public servers, or null when it may not. The public servers index mainnet only. A network
 * that has an owner's own server ([owned]) is read from that server alone, so the owner's addresses never reach a public server.
 */
internal fun publicElectrumFor(pool: ElectrumPool, network: BtcNetwork, owned: Set<BtcNetwork>): ElectrumPool? =
    pool.takeIf { network == BtcNetwork.MAINNET && network !in owned }

/** What an Electrum read of one account found: the used addresses of each chain, and the unspent outputs of the addresses that hold coins. */
internal class ElectrumRead(val receive: List<UsedAddress>, val change: List<UsedAddress>, val utxos: List<BtcUtxo>)

/**
 * Reads [account] from [pool]: the gap-limit walk of both chains at once, then the unspent outputs of the addresses that hold coins. Each window
 * of the walk is one batch, and so are the unspent outputs. [step] reports the addresses asked for so far, as the Esplora walk does. Returns null
 * when Electrum fails, so that the caller reads the account from Esplora. A cancellation is passed on. [usedBelow] is the receive hint of
 * [electrumChain], and [memo] and [nowMs] are those of [scanChain], which the walk fills the same way. A failed read leaves [memo] as it was, so
 * that the Esplora walk asks for every address.
 */
internal suspend fun electrumRead(
    pool: ElectrumPool,
    account: BtcWatchOnlyAccount,
    step: suspend (Int) -> Unit,
    usedBelow: Int = 0,
    memo: ConcurrentMap<String, MemoStats>,
    nowMs: Long,
): ElectrumRead? {
    val network = account.network
    val asked = AtomicInteger(0)
    val read: suspend (List<String>) -> List<AddressReading> = { addresses ->
        pool.readings(addresses.map { scripthash(scriptOf(network, it)) }).also { step(asked.addAndGet(addresses.size)) }
    }
    return try {
        val receive = mutableListOf<UsedAddress>()
        val change = mutableListOf<UsedAddress>()
        val walked = ConcurrentHashMap<String, MemoStats>()
        coroutineScope {
            launch { electrumChain(account::receiveAddress, read, receive, usedBelow, walked, nowMs) }
            launch { electrumChain(account::changeAddress, read, change, memo = walked, nowMs = nowMs) }
        }
        val holders = (receive + change).filter { it.reading.balance != 0L }
        val utxos = if (holders.isEmpty()) emptyList() else pool.unspent(holders.map { scripthash(scriptOf(network, it.address)) })
        memo.putAll(walked)
        ElectrumRead(receive, change, utxos)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/**
 * Walks one chain with the gap limit, as [scanChain] does, and finds the same used addresses. [read] answers one window at a time, in one batch: windows
 * of [BtcAccount.GAP_LIMIT] addresses, except that the first one reaches the gap after [usedBelow] (see [firstWindowOf]). The walk stops in the window
 * where the gap closes, so no address past the stop is counted. Each address up to the stop is remembered in [memo] at [nowMs], as [scanChain]
 * remembers the addresses it reads, so a send after the scan trusts these readings within the TTL.
 */
internal suspend fun electrumChain(
    addressAt: (Int) -> String,
    read: suspend (List<String>) -> List<AddressReading>,
    found: MutableList<UsedAddress> = mutableListOf(),
    usedBelow: Int = 0,
    memo: ConcurrentMap<String, MemoStats> = ConcurrentHashMap(),
    nowMs: Long = 0L,
): ChainScan {
    var index = 0
    var unused = 0
    var size = firstWindowOf(usedBelow, BtcAccount.GAP_LIMIT)
    while (unused < BtcAccount.GAP_LIMIT) {
        val window = List(size) { addressAt(index + it) }
        for ((offset, reading) in read(window).withIndex()) {
            memo[window[offset]] = MemoStats(reading, nowMs)
            if (reading.used) {
                found += UsedAddress(index + offset, window[offset], reading)
                unused = 0
            } else if (++unused == BtcAccount.GAP_LIMIT) {
                break
            }
        }
        index += size
        size = BtcAccount.GAP_LIMIT
    }
    return ChainScan(found)
}

/**
 * The used flag and the balance of each scripthash, in order. The histories come in one batch. The balances come in a second one, for the
 * scripthashes with a history, since an address without one holds no coins.
 */
private suspend fun ElectrumPool.readings(scripthashes: List<String>): List<AddressReading> = withServer { connection ->
    val histories = connection.scripthashHistories(scripthashes).map { it.getOrThrow() }
    val withHistory = scripthashes.indices.filter { histories[it].isNotEmpty() }
    val balances = connection.scripthashBalances(withHistory.map { scripthashes[it] }).map { it.getOrThrow() }
    val balanceAt = withHistory.zip(balances).toMap()
    scripthashes.indices.map { i ->
        val balance = balanceAt[i]
        AddressReading(
            used = histories[i].isNotEmpty(),
            confirmedSats = balance?.confirmedSats ?: 0L,
            unconfirmedSats = balance?.unconfirmedSats ?: 0L,
        )
    }
}

/**
 * The unspent outputs of [scripthashes], in one batch. Electrum gives an unconfirmed output a height of 0, or -1 when it spends another unconfirmed
 * output, so the block height is set only above zero.
 */
private suspend fun ElectrumPool.unspent(scripthashes: List<String>): List<BtcUtxo> = withServer { connection ->
    connection.scripthashUnspent(scripthashes).flatMap { it.getOrThrow() }
        .map { BtcUtxo(it.txid, it.vout, it.valueSats, it.height.takeIf { height -> height > 0 }) }
}
