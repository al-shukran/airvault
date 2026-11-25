package app.lumio.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirAmount
import app.lumio.ui.kit.ClearAirChangeLineStyle
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.watchlist.WatchedAsset
import app.lumio.watchlist.WatchlistSort
import java.util.Locale
import kotlin.math.abs

/** The width and height of a row's tick chart. */
private val TICK_WIDTH = 56.dp
private val TICK_HEIGHT = 28.dp

/** The height of the hero's tick chart, which fills the page gutters. */
private val HERO_CHART_HEIGHT = 92.dp

/** The space from the hero's numeral to its change line, to the freshness line, and to the chart. */
private val CHANGE_TOP = 4.dp
private val FRESHNESS_TOP = 2.dp
private val CHART_TOP = 10.dp

/** The freshness line's dot, and its gap to the words. */
private val FRESHNESS_DOT = 6.dp
private val FRESHNESS_GAP = 6.dp

/** The width of the Send capsule, which hugs "Send", and the gap between it and Receive. */
private val SEND_WIDTH = 129.dp
private val ACTIONS_GAP = 18.dp

/** The size of the sort arrow beside the sort control. */
private val SORT_ARROW = 14.dp

/** The hero's freshness line, and the sort control: 12 sp body text, and 13 sp semibold. */
private val freshnessStyle
    get() = ClearAirType.body.copy(fontSize = 12.sp, lineHeight = 16.sp)
private val sortStyle
    get() = ClearAirType.rowTitle.copy(fontSize = 13.sp, lineHeight = 18.sp)

/**
 * One watchlist row as Home shows it: its [name], the [detail] line under it such as "BTC · 0.2104 held", its [icon], its price and 24 h change, and the [ticks] of
 * its day's prices, oldest first. A null [priceUsd] or [change24hPct] is shown as absent, never as zero, and fewer than two [ticks] draw no chart.
 */
data class WatchlistRow(
    val asset: WatchedAsset,
    val name: String,
    val detail: String,
    val icon: AssetIconSpec,
    val priceUsd: String?,
    val change24hPct: Double?,
    val ticks: List<Double> = emptyList(),
)

/**
 * The whole market as the hero shows it: its total in dollars ([total], null when it is not read), its 24 h change, and the [freshness] line, such as
 * "Live · updated 8 s ago". [ticks] are the points of the chart under the total, oldest first, and fewer than two draw no chart.
 */
data class MarketHero(val total: String?, val change24hPct: Double?, val freshness: String?, val ticks: List<Double> = emptyList())

/**
 * The Home tab's body: the hero with the total market cap, Send and Receive under it, and the wallet's watchlist with its sort control. [rows] are already
 * in the order the list shows them. A test network shows no prices, because test coins have no value. [onSend] and [onReceive] open the wallet's Send and
 * Receive, and [onSort] opens the sort sheet. The header parts keep the page gutter; the rows are full-bleed, as every list row is.
 */
@Composable
fun HomeScreen(
    hero: MarketHero,
    rows: List<WatchlistRow>,
    sort: WatchlistSort,
    testnet: Boolean,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onSort: () -> Unit,
    onOpen: (WatchedAsset) -> Unit,
) {
    val palette = LocalClearAirPalette.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ClearAirEyebrow(text = "Total Market Cap", top = 0.dp, bottom = ClearAirSpacing.xxs)
        hero.total?.let { ClearAirAmount(text = it, style = ClearAirType.hero, dimUnit = true) }
            ?: Text(text = "—", style = ClearAirType.hero, color = palette.ink)
        hero.change24hPct?.let { change ->
            Text(
                text = "${changeText(change)} Today",
                modifier = Modifier.padding(top = CHANGE_TOP),
                style = ClearAirChangeLineStyle,
                color = if (change >= 0) palette.vaultBlue else palette.rose,
            )
        }
        hero.freshness?.let { Freshness(text = it) }
        if (hero.ticks.size >= 2) {
            val up = hero.change24hPct?.let { it >= 0 } ?: (hero.ticks.last() >= hero.ticks.first())
            TickChart(values = hero.ticks, up = up, modifier = Modifier.padding(top = CHART_TOP).fillMaxWidth().height(HERO_CHART_HEIGHT))
        }
        Row(
            modifier = Modifier.padding(top = CHART_TOP),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ACTIONS_GAP),
        ) {
            ClearAirPrimaryButton(text = "Send", onClick = onSend, modifier = Modifier.width(SEND_WIDTH))
            ClearAirTextAction(text = "Receive", onClick = onReceive)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ClearAirSpacing.lg - ClearAirSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ClearAirEyebrow(text = "Watchlist · ${rows.size}", top = 0.dp, bottom = 0.dp)
            SortControl(label = sort.label, onClick = onSort)
        }
        if (rows.isEmpty()) {
            Text(
                text = "Star an asset to watch it here.",
                style = ClearAirType.body,
                color = palette.ink2,
                modifier = Modifier.fillMaxWidth().padding(top = ClearAirSpacing.sm),
            )
        }
    }
    rows.forEachIndexed { index, row ->
        ClearAirRow(
            title = row.name,
            description = row.detail,
            leading = { AssetDisc(row.icon) },
            trailing = { WatchlistPrice(row = row, testnet = testnet) },
            onClick = { onOpen(row.asset) },
            divider = index > 0,
            chevron = false,
        )
    }
}

/** The freshness line under the hero: a grey dot, then the words. */
@Composable
private fun Freshness(text: String) {
    val palette = LocalClearAirPalette.current
    Row(modifier = Modifier.padding(top = FRESHNESS_TOP), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(FRESHNESS_DOT).clip(CircleShape).background(palette.ink3))
        Spacer(Modifier.width(FRESHNESS_GAP))
        Text(text = text, style = freshnessStyle, color = palette.ink2)
    }
}

/** The sort control of the watchlist: the order's name in ink, then a down arrow. It is a 48 dp touch target. */
@Composable
private fun SortControl(label: String, onClick: () -> Unit) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = ClearAirSpacing.touchTarget)
            .lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = sortStyle, color = palette.ink, maxLines = 1)
        Spacer(Modifier.width(ClearAirSpacing.xxs))
        SortArrow(color = palette.ink)
    }
}

/** The sort arrow of the proposal: a stem and a head, on the 24 unit grid at [SORT_ARROW]. */
@Composable
private fun SortArrow(color: Color) {
    Canvas(Modifier.size(SORT_ARROW)) {
        val unit = size.width / 24f
        val stroke = 1.6f * unit
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color = color, start = Offset(x1 * unit, y1 * unit), end = Offset(x2 * unit, y2 * unit), strokeWidth = stroke, cap = StrokeCap.Round)
        line(12f, 5f, 12f, 19f)
        line(7f, 14f, 12f, 19f)
        line(17f, 14f, 12f, 19f)
    }
}

/** The tick chart, price and 24 h change of a watchlist row, on the right. A test network shows nothing here. */
@Composable
private fun WatchlistPrice(row: WatchlistRow, testnet: Boolean) {
    if (testnet) return
    val palette = LocalClearAirPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (row.ticks.size >= 2) {
            val up = row.change24hPct?.let { it >= 0 } ?: (row.ticks.last() >= row.ticks.first())
            TickChart(values = row.ticks, up = up, modifier = Modifier.width(TICK_WIDTH).height(TICK_HEIGHT))
            Spacer(Modifier.width(ClearAirSpacing.sm))
        }
        Column(horizontalAlignment = Alignment.End) {
            row.priceUsd?.let { ClearAirAmount(text = it, style = ClearAirType.rowValue) }
            row.change24hPct?.let { change ->
                Text(text = changeText(change), style = ClearAirType.label, color = if (change >= 0) palette.vaultBlue else palette.rose)
            }
        }
    }
}

/** A 24 h change as Home writes it: an arrow for the direction, then the size to two places, such as "▲ 1.82%". */
internal fun changeText(percent: Double): String {
    val arrow = if (percent >= 0) "▲" else "▼"
    return "$arrow ${String.format(Locale.US, "%.2f", abs(percent))}%"
}
