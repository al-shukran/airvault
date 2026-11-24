package app.lumio.btc

import app.lumio.data.cache.CachePolicy
import app.lumio.data.cache.CacheUse
import app.lumio.data.cache.jsonCodec
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.Block
import app.lumio.data.provider.Tx
import app.lumio.data.scan.QueuedJob
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler

/** A transaction is final, and cached for good, once it has this many confirmations. */
const val FINAL_CONFIRMATIONS = 6L

/**
 * Scans the account of [job] and stores its result as the account's last known balance. The account the owner has open (FOREGROUND) also reads its
 * recent transactions. A portfolio scan keeps the transactions of the stored scan. A job whose account is no longer paired has nothing to scan, so it
 * returns at once. A scan that fails part way is stored as incomplete, then fails, and the job stays queued for the next pass. Such a scan replaces the
 * stored one only when it reads no less (see [isNotWorseThan]); otherwise the stored scan is kept, marked incomplete.
 */
suspend fun scanJob(
    job: QueuedJob,
    refs: List<BtcAccountRef>,
    scheduler: Scheduler,
    store: BtcStore,
    memo: AddressStatsCache,
    nowMs: () -> Long,
    step: suspend (Int) -> Unit,
) {
    val ref = refs.firstOrNull { it.key == job.job.accountRef } ?: return
    val detail = job.job.priority == Priority.FOREGROUND
    suspend fun save(scanned: BtcSnapshot) {
        val snapshot = if (detail) {
            scanned
        } else {
            val stored = store.snapshot(ref.key)?.takeIf { it.identity == ref.account.receiveAddress(0) }
            scanned.copy(transactions = stored?.transactions.orEmpty())
        }
        store.save(snapshot)
    }
    save(
        scanAccount(
            ref = ref,
            scheduler = scheduler,
            priority = job.job.priority,
            nowMs = nowMs(),
            memo = memo.forNetwork(ref.account.network.networkId()),
            detail = detail,
            step = step,
            onPartial = { partial ->
                val stored = store.snapshot(ref.key)?.takeIf { it.identity == ref.account.receiveAddress(0) }
                if (stored != null && !partial.isNotWorseThan(stored)) {
                    // The stored balances and addresses stay, and the refresh is marked incomplete.
                    store.save(stored.copy(complete = false))
                } else {
                    save(partial.copy(complete = false))
                }
            },
        ),
    )
}

/**
 * True when this partial scan reads no less than [stored]: it holds every address that [stored] holds, and its balance is not lower. A scan that
 * fails part way has not read the addresses after the failed request, so it can lack a stored address and the balance of that address.
 */
private fun BtcSnapshot.isNotWorseThan(stored: BtcSnapshot): Boolean =
    addresses.containsAll(stored.addresses) && confirmedSats + unconfirmedSats >= stored.confirmedSats + stored.unconfirmedSats

/**
 * The block [hash] of [net]. A block never changes, so the answer is cached for good, and a later read is served from the cache.
 */
suspend fun Scheduler.block(net: NetworkId, hash: String, priority: Priority = Priority.VISIBLE) =
    run(Block(net, hash), priority, CacheUse(CachePolicy.Immutable, "block/${net.chain}/$hash", jsonCodec()))

/**
 * The transaction [txid] of [net], read at chain tip [tipHeight]. Only a transaction with [FINAL_CONFIRMATIONS] confirmations is cached for good.
 * Until then the status is read again, since a reorg or a fresh block can still change it.
 */
suspend fun Scheduler.transaction(net: NetworkId, txid: String, tipHeight: Long, priority: Priority = Priority.VISIBLE) =
    run(
        Tx(net, txid),
        priority,
        CacheUse(CachePolicy.Immutable, "tx/${net.chain}/$txid", jsonCodec()) { status ->
            status.confirmationsAt(tipHeight) >= FINAL_CONFIRMATIONS
        },
    )
