package app.lumio.wallets

import app.airvault.core.model.ChainId
import app.lumio.data.net.Networks

/**
 * What a wallet screen shows of the reads of its chains. [checking] names the chain families whose read runs now, by namespace (such as [Networks.BIP122]).
 * [landed] names the families whose read has answered or failed since the side was shown: until then an account with no balance is still being checked.
 * [startedAtMs] is when the latest read of each family began, so a kept balance read before it is the last known one. [unreachable] names the chains
 * whose last read did not answer. [bitcoinDone] of [bitcoinTotal] Bitcoin accounts have a result in the running refresh. [pricing] is true while the
 * prices of the side's coins are read, and [priced] once that read has ended, so the total says "No price yet" only after it.
 */
data class WalletReads(
    val checking: Set<String> = emptySet(),
    val landed: Set<String> = emptySet(),
    val startedAtMs: Map<String, Long> = emptyMap(),
    val unreachable: Set<ChainId> = emptySet(),
    val bitcoinDone: Int = 0,
    val bitcoinTotal: Int = 0,
    val pricing: Boolean = false,
    val priced: Boolean = false,
)

/** What the balance slot of an account's row shows, in place of a value. */
sealed interface BalanceSlot {
    /** No balance is kept yet, and its chain family is being read or has not been read yet. */
    data object Checking : BalanceSlot

    /** No balance is kept, and its chain did not answer. */
    data object NotChecked : BalanceSlot

    /** The side has no native coin for the account, so there is no balance to show. */
    data object None : BalanceSlot

    /**
     * The kept balance. It shows while its chain is read again. [incomplete] when its Bitcoin scan failed part way, and [stale] when it was read before the
     * latest read of its chain began, so it is the last known balance.
     */
    data class Value(val incomplete: Boolean, val stale: Boolean) : BalanceSlot
}

/** Whether [account]'s kept balance was read before the latest read of its chain family began: then it is the last known balance, not this read's. */
fun isStale(account: WalletAccount, reads: WalletReads): Boolean {
    val started = reads.startedAtMs[account.namespace] ?: return false
    return account.balance != null && (account.readAtMs ?: 0L) < started
}

/** The balance slot of [account] given the reads of its wallet screen. A kept balance shows while its chain is read again. */
fun balanceSlotOf(account: WalletAccount, reads: WalletReads): BalanceSlot = when {
    account.balance != null -> BalanceSlot.Value(incomplete = account.incomplete, stale = isStale(account, reads))
    account.coin == null -> BalanceSlot.None
    account.namespace in reads.checking || account.namespace !in reads.landed -> BalanceSlot.Checking
    else -> BalanceSlot.NotChecked
}

/**
 * Whether the island of [family] says that its read did not answer. It says so only when that failure leaves one of its values missing, last known or
 * incomplete. An island whose values are all current says nothing, even when one of its reads failed, and an island that is read now says nothing yet.
 */
fun failureShown(accounts: List<WalletAccount>, family: String, reads: WalletReads): Boolean =
    family !in reads.checking && accounts.any { account ->
        account.namespace == family && account.id.chainId in reads.unreachable &&
            (account.balance == null || account.incomplete || isStale(account, reads))
    }

/** Whether the total, with no dollar value to show, says "Checking price…": the prices of the side are read, or have not been read yet. */
fun isCheckingPrice(reads: WalletReads): Boolean = reads.pricing || !reads.priced

/**
 * The status line of a wallet screen while a read runs: "Checking Bitcoin · 3 of 8 accounts" for the first family in chain order that is still checked,
 * and "Checking Ethereum" for a family whose read has no count. Null when no family is checked, and the screen then shows the age of the last complete read.
 */
fun checkingLine(reads: WalletReads): String? {
    val family = WalletAccounts.FAMILIES.firstOrNull { it in reads.checking } ?: return null
    val label = WalletAccounts.familyLabel(family)
    if (family != Networks.BIP122) return "Checking $label"
    val accounts = if (reads.bitcoinTotal == 1) "1 account" else "${reads.bitcoinTotal} accounts"
    return "Checking $label · ${reads.bitcoinDone} of $accounts"
}

/**
 * The note under a chain's island when its read did not answer, and the screen puts its Retry link after it. It says the last known balance is shown
 * only when the chain keeps one: "Couldn't reach Bitcoin. Showing the last known balance." or "Couldn't reach Bitcoin."
 */
fun unreachableNote(networkName: String, keptBalance: Boolean): String =
    if (keptBalance) "Couldn't reach $networkName. Showing the last known balance." else "Couldn't reach $networkName."

/** The chain namespaces that one read covers. Solana and Algorand are read together, so a Retry on either reads both. */
fun readFamilies(namespace: String): Set<String> = when (namespace) {
    Networks.SOLANA, Networks.ALGORAND -> setOf(Networks.SOLANA, Networks.ALGORAND)
    else -> setOf(namespace)
}

/**
 * [unreachable] with the chains of the read's [namespaces] replaced by the [failed] chains of that read. A read that landed says which of its chains did
 * not answer, and the chains of its other families stay as they were.
 */
fun landedUnreachable(unreachable: Set<ChainId>, namespaces: Set<String>, failed: Set<ChainId>): Set<ChainId> =
    unreachable.filterNot { it.namespace in namespaces }.toSet() + failed
