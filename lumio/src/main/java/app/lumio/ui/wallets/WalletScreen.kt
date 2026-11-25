package app.lumio.ui.wallets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.app.familyLabel
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.Clock
import app.lumio.data.WallClock
import app.lumio.home.HomeFormat
import app.lumio.network.NetworkMode
import app.lumio.ui.kit.ClearAirAmount
import app.lumio.ui.kit.ClearAirChangeLine
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirMonogram
import app.lumio.ui.kit.ClearAirRefreshBox
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirWalletEmblem
import app.lumio.ui.kit.WalletEmblemTone
import app.lumio.ui.kit.LocalFiat
import app.lumio.ui.network.label
import app.lumio.wallets.BalanceSlot
import app.lumio.wallets.WalletAccount
import app.lumio.wallets.WalletReads
import app.lumio.wallets.balanceSlotOf
import app.lumio.wallets.failureShown
import app.lumio.wallets.isCheckingPrice
import app.lumio.wallets.unreachableNote
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The emblem beside the wallet's detail line, as C6 draws it. */
private val DETAIL_EMBLEM = 24.dp

/**
 * One paired wallet (C2), as the body of its page: its total under a "Total" eyebrow with its change over 24 hours ([changePct] and [changeUsd]) and the
 * freshness line, and the line that says how many accounts it holds in how many Lensly apps. Each chain family is an island under its own eyebrow and
 * its total. An account opens its own page. A family's
 * accounts that hold nothing, or are worth under [FOLD_BELOW_USD], fold into one "More Accounts" row that opens them in place. Pulling down reads the
 * balances again. The page's top bar is the shell's, so this has none. [tokenCounts] holds the tokens each Ethereum account holds, and [usdOf] gives an
 * account's value in dollars. [tokenUsd] gives the dollars of the tokens an account holds, which count toward its fold. [notice] names a read that failed as a
 * whole. [reads] says which chains are checked and which did not answer: an island that has a value missing, last known or incomplete because of a chain
 * that did not answer says so under its eyebrow, with a Retry that calls [onRetry] with the chain's family. That Retry is hidden while [refreshing].
 */
@Composable
fun WalletScreen(
    mode: NetworkMode,
    accounts: List<WalletAccount>,
    tokenCounts: Map<WalletAccount, Int>,
    totalUsd: Double?,
    usdOf: (WalletAccount) -> Double?,
    showTotal: Boolean,
    showBalances: Boolean,
    updatedAtMs: Long?,
    notice: String?,
    onAccount: (WalletAccount) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    tokenUsd: Map<WalletAccount, Double> = emptyMap(),
    changePct: Double? = null,
    changeUsd: Double? = null,
    refreshing: Boolean = false,
    refreshByPull: Boolean = false,
    pairedAtMs: Long? = null,
    tone: WalletEmblemTone = WalletEmblemTone.BLUE,
    reads: WalletReads = WalletReads(),
    onRetry: (String) -> Unit = {},
    clock: Clock = WallClock,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    ClearAirRefreshBox(refreshByPull = refreshByPull, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = ClearAirSpacing.md)) {
            item(key = "hero") {
                WalletHero(
                    detail = walletDetailLine(accounts, pairedAtMs, zone),
                    tone = tone,
                    totalUsd = totalUsd,
                    changePct = changePct,
                    changeUsd = changeUsd,
                    showTotal = showTotal,
                    reads = reads,
                    refreshing = refreshing,
                    updatedAtMs = updatedAtMs,
                    clock = clock,
                )
            }
            notice?.let { message ->
                item(key = "notice") {
                    WalletNotice(message = message, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter))
                }
            }
            if (accounts.isEmpty()) {
                item(key = "none") {
                    Text(
                        text = "This wallet has no accounts on ${mode.label().lowercase()} yet.",
                        modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md),
                        style = ClearAirType.body,
                        color = LocalClearAirPalette.current.ink2,
                    )
                }
            }
            accountsByChain(accounts).forEach { (family, group) ->
                item(key = "chain-$family") {
                    ChainIsland(
                        family = family,
                        group = group,
                        tokenCounts = tokenCounts,
                        usdOf = usdOf,
                        tokenUsd = tokenUsd,
                        showTotal = showTotal,
                        showBalances = showBalances,
                        reads = reads,
                        refreshing = refreshing,
                        clock = clock,
                        onRetry = onRetry,
                        onAccount = onAccount,
                    )
                }
            }
        }
    }
}

/**
 * The hero of a wallet: "Total" over its total, its change over 24 hours, the line that says what it holds, and the freshness line. A wallet with no
 * price says so in place of its total.
 */
@Composable
private fun WalletHero(
    detail: String?,
    tone: WalletEmblemTone,
    totalUsd: Double?,
    changePct: Double?,
    changeUsd: Double?,
    showTotal: Boolean,
    reads: WalletReads,
    refreshing: Boolean,
    updatedAtMs: Long?,
    clock: Clock,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.sm)) {
        if (detail != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClearAirWalletEmblem(tile = DETAIL_EMBLEM, tone = tone)
                Spacer(Modifier.width(ClearAirSpacing.xs))
                Text(text = detail, style = ClearAirType.body, color = LocalClearAirPalette.current.ink2)
            }
        }
        ClearAirEyebrow(text = "Total")
        if (totalUsd == null) {
            if (isCheckingPrice(reads)) {
                ShimmerText(text = "Checking price…", style = ClearAirType.rowTitle)
            } else {
                Text(text = "No price yet", style = ClearAirType.rowTitle, color = LocalClearAirPalette.current.ink2)
            }
        } else {
            ClearAirAmount(text = LocalFiat.current.amount(totalUsd), hidden = !showTotal, style = ClearAirType.total)
        }
        ClearAirChangeLine(changePct = changePct, changeUsd = changeUsd, shown = showTotal, modifier = Modifier.padding(top = ClearAirSpacing.xxs))
        FreshnessLine(
            refreshing = refreshing,
            updatedAtMs = updatedAtMs,
            clock = clock,
            modifier = Modifier.padding(top = ClearAirSpacing.xxs),
        )
    }
}

/**
 * The accounts of one chain [family] as an island under its eyebrow: "Ethereum App · 10" with the family's total at the right. Its accounts that
 * hold nothing, or are under the threshold, fold into one row. Opening that row shows them in place, and the island keeps its own state.
 */
@Composable
private fun ChainIsland(
    family: String,
    group: List<WalletAccount>,
    tokenCounts: Map<WalletAccount, Int>,
    usdOf: (WalletAccount) -> Double?,
    tokenUsd: Map<WalletAccount, Double>,
    showTotal: Boolean,
    showBalances: Boolean,
    reads: WalletReads,
    refreshing: Boolean,
    clock: Clock,
    onRetry: (String) -> Unit,
    onAccount: (WalletAccount) -> Unit,
) {
    val fold = foldAccounts(group, usdOf, tokenUsdOf = { tokenUsd[it] })
    var expanded by rememberSaveable(family) { mutableStateOf(false) }
    val rows = if (expanded) group else fold.shown
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = ClearAirSpacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClearAirEyebrow(text = "${familyLabel(family)} App · ${group.size}", modifier = Modifier.weight(1f))
            groupUsd(group, usdOf)?.let { usd -> ClearAirAmount(text = LocalFiat.current.amount(usd), hidden = !showTotal, style = ClearAirType.rowValue) }
        }
        // A chain that did not answer says so once, under the eyebrow, with a Retry. It says so only while a value is missing, last known or incomplete
        // because of it. The rest of the island stays live. The Retry waits while a refresh runs, since that refresh reads the chain already.
        if (failureShown(group, family, reads)) {
            ChainFailureNote(
                text = unreachableNote(group.first().networkName, keptBalance = group.any { it.balance != null }),
                showRetry = !refreshing,
                onRetry = { onRetry(family) },
                modifier = Modifier.padding(bottom = ClearAirSpacing.xs),
            )
        }
        rows.forEachIndexed { index, account ->
            AccountItem(
                account = account,
                tokens = tokenCounts[account] ?: 0,
                usd = usdOf(account),
                showTotal = showTotal,
                showBalances = showBalances,
                slot = balanceSlotOf(account, reads),
                clock = clock,
                divider = index > 0,
                onClick = { onAccount(account) },
            )
        }
        if (fold.folded.isNotEmpty()) {
            FoldRow(
                count = fold.folded.size,
                summary = foldSummary(fold),
                usd = groupUsd(fold.folded, usdOf),
                showTotal = showTotal,
                divider = rows.isNotEmpty(),
                onClick = { expanded = !expanded },
            )
        }
    }
}

/**
 * One account of a wallet: its monogram, its title and its short address with its asset count, and its value at the right. The value is its dollars
 * when it has a price, with its coins under it; a balance no read has reached is a bar of the value's size.
 */
@Composable
private fun AccountItem(
    account: WalletAccount,
    tokens: Int,
    usd: Double?,
    showTotal: Boolean,
    showBalances: Boolean,
    slot: BalanceSlot,
    clock: Clock,
    divider: Boolean,
    onClick: () -> Unit,
) {
    ClearAirRow(
        title = accountTitle(account),
        index = accountIndex(account),
        description = accountLine(account, tokens),
        leading = { ClearAirMonogram(text = monogramOf(account)) },
        trailing = { AccountValue(account = account, usd = usd, showTotal = showTotal, showBalances = showBalances, slot = slot, clock = clock) },
        onClick = onClick,
        divider = divider,
        chevron = false,
    )
}

/**
 * The value of an account at the right of its row: its dollars over its coins, or only its coins without a price. Without a kept balance it says what the
 * read is doing: "Checking…" while its chain is read, or "Not checked" when its chain did not answer. An incomplete Bitcoin balance says so under it, and a
 * last known balance says how old it is.
 */
@Composable
private fun AccountValue(account: WalletAccount, usd: Double?, showTotal: Boolean, showBalances: Boolean, slot: BalanceSlot, clock: Clock) {
    val balance = account.balance
    Column(horizontalAlignment = Alignment.End) {
        if (balance == null) {
            when (slot) {
                BalanceSlot.Checking -> ShimmerText(text = "Checking…", style = ClearAirType.label)
                BalanceSlot.NotChecked -> Text(text = "Not checked", style = ClearAirType.label, color = LocalClearAirPalette.current.ink2)
                else -> Unit
            }
            return@Column
        }
        val coins = "${HomeFormat.coins(balance.balance)} ${balance.symbol}"
        if (usd == null) {
            ClearAirAmount(text = coins, hidden = !showBalances, style = ClearAirType.rowValue)
        } else {
            ClearAirAmount(text = LocalFiat.current.amount(usd), hidden = !showTotal, style = ClearAirType.rowValue)
            ClearAirAmount(text = coins, hidden = !showBalances, style = ClearAirType.label, color = LocalClearAirPalette.current.ink2)
        }
        if (slot is BalanceSlot.Value && slot.incomplete) {
            Text(text = "Incomplete", style = ClearAirType.label, color = LocalClearAirPalette.current.ink2)
        }
        account.readAtMs?.takeIf { slot is BalanceSlot.Value && slot.stale }?.let { AsOfLine(readAtMs = it, clock = clock) }
    }
}

/** The row that holds an island's folded accounts: "+7" as its monogram, "7 More Accounts", what they hold, and their total. Opening it shows them. */
@Composable
private fun FoldRow(count: Int, summary: String, usd: Double?, showTotal: Boolean, divider: Boolean, onClick: () -> Unit) {
    ClearAirRow(
        title = moreAccountsTitle(count),
        description = summary,
        leading = { ClearAirMonogram(text = "+$count") },
        trailing = if (usd == null) null else ({ ClearAirAmount(text = LocalFiat.current.amount(usd), hidden = !showTotal, style = ClearAirType.rowValue) }),
        onClick = onClick,
        divider = divider,
        chevron = false,
    )
}

/**
 * The line under a wallet's total: how many accounts it holds on the side and in how many Lensly apps, and the day it was paired, such as "30 accounts
 * in 4 Lensly apps · paired 21 Nov". Null when it holds no account on the side.
 */
internal fun walletDetailLine(accounts: List<WalletAccount>, pairedAtMs: Long?, zone: ZoneId): String? {
    if (accounts.isEmpty()) return null
    val apps = accounts.map { it.namespace }.distinct().size
    val appsText = if (apps == 1) "1 Lensly app" else "$apps Lensly apps"
    val accountsText = if (accounts.size == 1) "1 account" else "${accounts.size} accounts"
    val paired = pairedAtMs?.let { " · paired ${PAIRED_DATE.withZone(zone).format(Instant.ofEpochMilli(it))}" }.orEmpty()
    return "$accountsText in $appsText$paired"
}

/** The day a wallet was paired, as "21 Nov". */
private val PAIRED_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
