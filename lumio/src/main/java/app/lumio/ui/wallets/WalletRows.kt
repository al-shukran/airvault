package app.lumio.ui.wallets

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lensly.ui.theme.LocalReducedMotion
import app.lumio.data.Clock
import app.lumio.ui.home.agoText
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.wallets.WalletAccount
import app.lumio.wallets.WalletAccounts
import kotlinx.coroutines.delay

/** The indent of a row's divider in a list: past the gutter, the 36 dp leading mark and the gap after it, so the line starts at the row's text. */
internal val ROW_DIVIDER_INDENT = ClearAirSpacing.gutter + 36.dp + ClearAirSpacing.sm

/** One minute in milliseconds: the freshness line ages once a minute. */
private const val MINUTE_MS = 60_000L

/** An account that holds nothing, or is worth less than this many dollars, folds into its family's "More Accounts" row. */
internal const val FOLD_BELOW_USD = 13_000.0

/** The threshold as the fold row names it. */
private const val FOLD_THRESHOLD_LABEL = "\$13K"

/** The line under a wallet's name: its accounts and the chain families they sit on, such as "30 accounts · 4 apps". */
internal fun summaryLine(accounts: List<WalletAccount>): String {
    if (accounts.isEmpty()) return "No accounts"
    return "${plural(accounts.size, "account")} · ${plural(accounts.map { it.namespace }.distinct().size, "app")}"
}

private fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

/** The accounts of each chain family on the side, in family order, by CAIP-2 namespace. A family with no account has no island. */
internal fun accountsByChain(accounts: List<WalletAccount>): List<Pair<String, List<WalletAccount>>> =
    WalletAccounts.FAMILIES.mapNotNull { family -> accounts.filter { it.namespace == family }.takeIf { it.isNotEmpty() }?.let { family to it } }

/**
 * The line under a total: "Checking…" while a read runs, else the age of the last complete read. A read that did not answer keeps the time before it.
 * Every screen that shows a total says it this way.
 */
internal fun freshnessLine(refreshing: Boolean, updatedAtMs: Long?, nowMs: Long): String = when {
    refreshing -> "Checking…"
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

/** An address as a list shows it: its first eight and last six characters. */
internal fun shortAddress(address: String): String = "${address.take(8)}…${address.takeLast(6)}"

/** The title of an account: the name the owner gave it in Lensly, or "Account" and its place in the wallet. */
internal fun accountTitle(account: WalletAccount): String = account.name ?: "Account ${account.index + 1}"

/**
 * The two letters of an account's monogram: the capitals of its name, else the name's first two letters. An account with no name has its chain's
 * letter and its place, such as "E1" for the first Ethereum account.
 */
internal fun monogramOf(account: WalletAccount): String {
    val name = account.name?.trim().orEmpty()
    val capitals = name.filter { it.isUpperCase() }
    val letters = when {
        capitals.length >= 2 -> capitals.take(2)
        name.length >= 2 -> name.take(2)
        else -> ""
    }
    if (letters.isNotEmpty()) return letters.uppercase()
    return "${WalletAccounts.familyLabel(account.namespace).first()}${account.index + 1}"
}

/**
 * The line under an account's title: its short address, then its script for Bitcoin or its count of assets for the rest. The count needs a read of
 * the native coin, so an account no read has reached shows its address alone.
 */
internal fun accountLine(account: WalletAccount, tokens: Int): String {
    val address = shortAddress(account.address)
    val detail = account.script ?: account.balance?.let { plural(1 + tokens, "asset") }
    return if (detail == null) address else "$address · $detail"
}

/** The sum of the dollar values [usdOf] gives the [accounts], or null when none of them has a price. */
internal fun groupUsd(accounts: List<WalletAccount>, usdOf: (WalletAccount) -> Double?): Double? =
    accounts.mapNotNull(usdOf).takeIf { it.isNotEmpty() }?.sum()

/** The accounts of one chain family that its island shows, and the ones that fold into its "More Accounts" row. */
internal class AccountFold(val shown: List<WalletAccount>, val folded: List<WalletAccount>) {
    /** The folded accounts that hold nothing. */
    val empty: Int = folded.count { it.balance?.balance?.signum() == 0 }

    /** The folded accounts that hold something under [FOLD_BELOW_USD]. */
    val under: Int = folded.size - empty
}

/**
 * Splits a family's [accounts]: one whose value folds away when it is under [FOLD_BELOW_USD]. The value is its native coin's dollars from [usdOf]
 * plus its tokens' dollars from [tokenUsdOf], so an account that holds nothing but tokens worth the threshold stays. An account that no read has
 * reached always shows, so its bar keeps its place until its balance lands, and so does one whose coin has no price while it holds a coin.
 */
internal fun foldAccounts(
    accounts: List<WalletAccount>,
    usdOf: (WalletAccount) -> Double?,
    tokenUsdOf: (WalletAccount) -> Double? = { null },
): AccountFold {
    val (folded, shown) = accounts.partition { account ->
        val balance = account.balance ?: return@partition false
        val coin = usdOf(account)
        if (coin == null && balance.balance.signum() != 0) return@partition false
        (coin ?: 0.0) + (tokenUsdOf(account) ?: 0.0) < FOLD_BELOW_USD
    }
    return AccountFold(shown = shown, folded = folded)
}

/** The title of a fold row: "7 More Accounts", or "1 More Account". */
internal fun moreAccountsTitle(count: Int): String = if (count == 1) "1 More Account" else "$count More Accounts"

/** The line under a fold row: how many of its accounts hold nothing and how many are under the threshold, such as "4 empty, 3 under $13K". */
internal fun foldSummary(fold: AccountFold): String = listOfNotNull(
    fold.empty.takeIf { it > 0 }?.let { "$it empty" },
    fold.under.takeIf { it > 0 }?.let { "$it under $FOLD_THRESHOLD_LABEL" },
).joinToString(", ")

/**
 * The freshness line of a total, which ages once a minute. Its text changes as a read starts and ends, and nothing else on the page moves.
 */
@Composable
internal fun FreshnessLine(refreshing: Boolean, updatedAtMs: Long?, clock: Clock, modifier: Modifier = Modifier) {
    val nowMs by produceState(clock.nowMs(), clock) {
        while (true) {
            value = clock.nowMs()
            delay(MINUTE_MS)
        }
    }
    Text(
        text = freshnessLine(refreshing, updatedAtMs, nowMs),
        modifier = modifier,
        style = ClearAirType.body,
        color = LocalClearAirPalette.current.ink2,
    )
}

/** The age of a kept balance under its value, "as of 4 min ago", while the latest read has not reached it. It counts again each minute, as [FreshnessLine] does. */
@Composable
internal fun AsOfLine(readAtMs: Long, clock: Clock) {
    val nowMs by produceState(clock.nowMs(), clock) {
        while (true) {
            value = clock.nowMs()
            delay(MINUTE_MS)
        }
    }
    Text(text = "as of ${agoText(nowMs - readAtMs)}", style = ClearAirType.label, color = LocalClearAirPalette.current.ink2)
}

/** The sweep of a checking line's sheen, in milliseconds: the same crossing as a bar's sheen. */
private const val CHECKING_SHEEN_MS = 1600

/**
 * A line that a read is still filling in, such as "Checking…". A sheen crosses its letters every 1.6 s, and only the letters take it, so the page under the
 * line stays still. Under reduced motion the line stays still too.
 */
@Composable
internal fun ShimmerText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    if (LocalReducedMotion.current) {
        Text(text = text, modifier = modifier, style = style, color = palette.ink2)
        return
    }
    val transition = rememberInfiniteTransition(label = "checking line")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(CHECKING_SHEEN_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "checking line sheen",
    )
    val sheen = palette.ink.copy(alpha = 0.3f)
    Text(
        text = text,
        modifier = modifier
            // Offscreen, so the sheen blends only with the letters drawn here, and not with the page under them.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val band = size.width * 0.5f
                val centre = (sweep * 2f - 1f) * size.width
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, sheen, Color.Transparent),
                        startX = centre - band / 2f,
                        endX = centre + band / 2f,
                    ),
                    blendMode = BlendMode.SrcAtop,
                )
            },
        style = style,
        color = palette.ink2,
    )
}

/**
 * A chain that did not answer, under its island: a spark, its failure in [text], and a Retry that reads that chain again. The Retry is shown only when
 * [showRetry] is true.
 */
@Composable
internal fun ChainFailureNote(text: String, showRetry: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(8.dp)) { drawCircle(color = palette.spark) }
        Spacer(Modifier.width(ClearAirSpacing.xs))
        // The note takes the width the Retry action leaves, so a long network name wraps and the action stays in view.
        Text(text = text, style = ClearAirType.body, color = palette.ink2, modifier = Modifier.weight(1f))
        if (showRetry) {
            Spacer(Modifier.width(ClearAirSpacing.xs))
            ClearAirTextAction(text = "Retry", onClick = onRetry)
        }
    }
}

/** A note about a read that did not answer. It wraps, and it never blocks the page under it. */
@Composable
internal fun WalletNotice(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        modifier = modifier.padding(vertical = 4.dp),
        style = ClearAirType.label,
        color = LocalClearAirPalette.current.rose,
    )
}

/** The hairline between two rows of an island, from [indent] to the gutter on the right, so it begins where the row's text does. */
@Composable
internal fun RowDivider(indent: Dp) {
    Box(
        modifier = Modifier
            .padding(start = indent, end = ClearAirSpacing.gutter)
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalClearAirPalette.current.hairline),
    )
}
