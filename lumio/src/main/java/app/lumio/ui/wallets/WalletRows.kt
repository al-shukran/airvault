package app.lumio.ui.wallets

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lensly.core.registry.NetworkEntry
import app.lensly.ui.components.LumioRow
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LocalReducedMotion
import app.lensly.ui.theme.LumenMotion
import app.lumio.data.Clock
import app.lumio.data.net.Networks
import app.lumio.home.HomeFormat
import app.lumio.home.Masking
import app.lumio.network.NetworkMode
import app.lumio.ui.home.AssetIcon
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.home.letterOf
import app.lumio.ui.kit.AnimatedAmount
import app.lumio.ui.kit.LumenRing
import app.lumio.ui.kit.LumioAssetIcon
import app.lumio.ui.kit.rememberLumioHaptics
import app.lumio.wallets.WalletAccount
import app.lumio.wallets.WalletAccounts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


/** The text of an account with no balance read. */
private const val NOT_READ = "Not read yet"

/** One minute in milliseconds: the freshness line ages once a minute. */
private const val MINUTE_MS = 60_000L

/** How far the Lumen ring travels as the owner pulls, from its rest above the list to its place at the threshold. */
private val PULL_TRAVEL = PullToRefreshDefaults.IndicatorMaxDistance

/** The size of the Lumen ring as a pull indicator and as a spinner beside a status line. */
private val PULL_RING = 24.dp
private val STATUS_RING = 16.dp

/** How far an item rises as it enters (section 3.4). */
private val STAGGER_RISE = 12.dp

/** The time the enter stagger takes for the last item it plays on, in milliseconds. */
private val STAGGER_SPAN_MS = LumenMotion.STAGGER.toLong() * (LumenMotion.STAGGER_MAX_ITEMS - 1) + LumenMotion.LONG

/** The line under a wallet's name: its chains and accounts, such as "4 chains · 7 accounts", or "No accounts" when it has none on the side. */
internal fun summaryLine(accounts: List<WalletAccount>): String {
    if (accounts.isEmpty()) return "No accounts"
    return "${plural(accounts.map { it.namespace }.distinct().size, "chain")} · ${plural(accounts.size, "account")}"
}

private fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

/** The accounts of each chain family on the side, in family order, by CAIP-2 namespace. A family with no account has no island. */
internal fun accountsByChain(accounts: List<WalletAccount>): List<Pair<String, List<WalletAccount>>> =
    WalletAccounts.FAMILIES.mapNotNull { family -> accounts.filter { it.namespace == family }.takeIf { it.isNotEmpty() }?.let { family to it } }

/** The line under a total: "Refreshing…" while a read runs, else the age of the last complete read. A read that did not answer keeps the time before it. */
internal fun freshnessLine(refreshing: Boolean, updatedAtMs: Long?, nowMs: Long): String = when {
    refreshing -> "Refreshing…"
    updatedAtMs == null -> "Not updated yet"
    else -> updatedAgo(nowMs - updatedAtMs)
}

/** "Updated just now" under a minute, "Updated 3 min ago" under an hour, and then "Updated 2 h ago". */
internal fun updatedAgo(ageMs: Long): String {
    val minutes = (ageMs / MINUTE_MS).coerceAtLeast(0L)
    return when {
        minutes < 1 -> "Updated just now"
        minutes < 60 -> "Updated $minutes min ago"
        else -> "Updated ${minutes / 60} h ago"
    }
}

/** A dollar amount split before its cents, such as "$12,480" and ".20". An amount without cents has an empty second part. */
internal fun splitCents(amount: String): Pair<String, String> {
    val dot = amount.lastIndexOf('.')
    return if (dot < 0) amount to "" else amount.substring(0, dot) to amount.substring(dot)
}

/** The delay of item [index]'s enter, in milliseconds: 30 ms per item, and the items from the eighth share the eighth's delay (section 3.4). */
internal fun staggerDelayMs(index: Int): Long = minOf(index, LumenMotion.STAGGER_MAX_ITEMS - 1).toLong() * LumenMotion.STAGGER

/** An address as a list shows it: its first eight and last six characters. */
internal fun shortAddress(address: String): String = "${address.take(8)}…${address.takeLast(6)}"

/** The token count of an account, as the line under its balance: "+2 tokens", or null when it holds none. */
internal fun tokenLine(tokens: Int): String? = when {
    tokens <= 0 -> null
    tokens == 1 -> "+1 token"
    else -> "+$tokens tokens"
}

/** The icon of an account row: the Bitcoin mark, the Ethereum network's logo on [evmChainId], or the first letter of a Solana or Algorand coin. */
internal fun accountIconSpec(account: WalletAccount, evmChainId: Long, network: (Long) -> NetworkEntry?): AssetIconSpec = when (account.namespace) {
    Networks.BIP122 -> AssetIconSpec.Bitcoin
    Networks.EIP155 -> AssetIconSpec.Chain(network(evmChainId))
    else -> AssetIconSpec.Letter(letterOf(account.balance?.symbol ?: WalletAccounts.familyLabel(account.namespace)))
}

/**
 * Whether a list plays its enter stagger now (section 3.4). It plays once: on the first composition of the screen. The flag is saved, so a return to the
 * screen never replays it, and reduced motion plays none.
 */
@Composable
internal fun rememberStagger(): Boolean {
    val played = rememberSaveable { mutableStateOf(false) }
    val running = remember { mutableStateOf(!played.value) }
    LaunchedEffect(Unit) {
        played.value = true
        delay(STAGGER_SPAN_MS)
        running.value = false
    }
    return running.value && !LocalReducedMotion.current
}

/**
 * The enter of list item [index] while [running] (section 3.4): it fades in on the effects spring and rises 12 dp on the spatial one, after its delay.
 * An item that is not running is shown in place, and an item that starts its enter keeps it to the end.
 */
@Composable
internal fun Modifier.staggeredEnter(index: Int, running: Boolean): Modifier {
    val fade = remember { Animatable(if (running) 0f else 1f) }
    val rise = remember { Animatable(if (running) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!running) return@LaunchedEffect
        delay(staggerDelayMs(index))
        launch { fade.animateTo(1f, LumenMotion.effects) }
        rise.animateTo(1f, LumenMotion.spatial)
    }
    return graphicsLayer {
        alpha = fade.value
        translationY = (1f - rise.value) * STAGGER_RISE.toPx()
    }
}

/**
 * The total of a wallet or of all wallets (section 2.1): its whole dollars in the hero face, its cents in the same size and the secondary colour. Hidden,
 * it shows the mask; without a price it says so. [modifier] is laid on the whole amount, so a total can move between screens.
 */
@Composable
internal fun TotalAmount(usd: Double?, shown: Boolean, modifier: Modifier = Modifier) {
    if (usd == null) {
        Text(
            text = "No price yet",
            modifier = modifier,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (!shown) {
        AnimatedAmount(text = HomeFormat.usd(usd), hidden = true, modifier = modifier)
        return
    }
    val (whole, cents) = splitCents(HomeFormat.usd(usd))
    Row(modifier = modifier) {
        AnimatedAmount(text = whole)
        if (cents.isNotEmpty()) AnimatedAmount(text = cents, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The freshness line of a total (section 2.1 and 3.4): a Lumen ring spins with "Refreshing…" while a read runs, else "Updated just now" or its age. The age
 * is read again once a minute, and the text cross-fades when a read ends.
 */
@Composable
internal fun FreshnessLine(refreshing: Boolean, updatedAtMs: Long?, clock: Clock, modifier: Modifier = Modifier) {
    val nowMs by produceState(clock.nowMs(), clock) {
        while (true) {
            value = clock.nowMs()
            delay(MINUTE_MS)
        }
    }
    val line = freshnessLine(refreshing, updatedAtMs, nowMs)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (refreshing) {
            LumenRing(progress = 1f, size = STATUS_RING, spinning = true)
            Spacer(Modifier.width(LenslySpacing.xs))
        }
        Crossfade(targetState = line, animationSpec = if (LocalReducedMotion.current) snap() else LumenMotion.effects, label = "freshness") { text ->
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The pull to refresh of a wallet list (section 3.4): the Lumen ring draws with the pull and gives a select haptic at the threshold. While [refreshByPull] the
 * ring spins, and when the read ends it closes to a dot and goes. A refresh the owner did not pull draws no ring over the list: its freshness line says
 * "Refreshing…" instead.
 */
@Composable
internal fun LumenRefreshBox(
    refreshByPull: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    val haptics = rememberLumioHaptics()
    val pastThreshold by remember { derivedStateOf { state.distanceFraction >= 1f } }
    LaunchedEffect(pastThreshold) {
        if (pastThreshold) haptics.select()
    }
    PullToRefreshBox(
        isRefreshing = refreshByPull,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        contentAlignment = Alignment.TopCenter,
        indicator = {
            val pulled = if (refreshByPull) 1f else state.distanceFraction.coerceIn(0f, 1f)
            LumenRing(
                progress = pulled,
                size = PULL_RING,
                spinning = refreshByPull,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        alpha = pulled
                        translationY = pulled * PULL_TRAVEL.toPx() - PULL_RING.toPx()
                    },
            )
        },
        content = content,
    )
}

/** The balance of [account] in its coin, such as "1.5 MON". The mask replaces it while the owner hides balances. */
@Composable
internal fun AccountBalance(account: WalletAccount, shown: Boolean, modifier: Modifier = Modifier) {
    val amount = account.balance?.let { "${HomeFormat.coins(it.balance)} ${it.symbol}" } ?: NOT_READ
    Text(
        text = Masking.amount(amount, hidden = !shown),
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}

/**
 * An account's row in the wallet's island: its chain's logo, its title, its short address under the title, and its balance with its token count under
 * the balance. A tap opens the account.
 */
@Composable
internal fun AccountRowItem(
    account: WalletAccount,
    icon: AssetIconSpec,
    tokens: Int,
    mode: NetworkMode,
    showBalance: Boolean,
    running: Boolean,
    index: Int,
    onClick: () -> Unit,
) {
    LumioRow(
        title = "Account ${account.index + 1}",
        description = shortAddress(account.address),
        leading = {
            LumioAssetIcon(testnet = mode == NetworkMode.TESTNET) {
                AssetIcon(spec = icon, size = 24.dp)
            }
        },
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                AccountBalance(account = account, shown = showBalance)
                tokenLine(tokens)?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                    )
                }
            }
        },
        onClick = onClick,
        modifier = Modifier.staggeredEnter(index, running),
    )
}

