package app.lumio.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirAmount
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.watchlist.WatchlistSort
import java.util.Locale
import kotlin.math.abs

/** The width and height of a row's tick chart. */
private val TICK_WIDTH = 56.dp
private val TICK_HEIGHT = 28.dp

/**
 * One watchlist row as Home shows it: its [name], the [detail] line under it such as "BTC · 0.2104 held", its price and 24 h change, and the [ticks] of
 * its day's prices, oldest first. A null [priceUsd] or [change24hPct] is shown as absent, never as zero, and fewer than two [ticks] draw no chart.
 */
data class WatchlistRow(
    val name: String,
    val detail: String,
    val priceUsd: String?,
    val change24hPct: Double?,
    val ticks: List<Double> = emptyList(),
)

/**
 * The whole market as the hero shows it: its total in dollars ([total], null when it is not read), its 24 h change, and the [freshness] line, such as
 * "Live · updated 8 s ago".
 */
data class MarketHero(val total: String?, val change24hPct: Double?, val freshness: String?)

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
) {
    val palette = LocalClearAirPalette.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ClearAirEyebrow(text = "Total Market Cap")
        Spacer(Modifier.height(ClearAirSpacing.xxs))
        Text(text = hero.total ?: "—", style = ClearAirType.hero, color = palette.ink)
        hero.change24hPct?.let { change ->
            Text(text = "${changeText(change)} Today", style = ClearAirType.label, color = if (change >= 0) palette.vaultBlue else palette.rose)
        }
        hero.freshness?.let { Text(text = it, style = ClearAirType.label, color = palette.ink2) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ClearAirSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClearAirPrimaryButton(text = "Send", onClick = onSend, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(ClearAirSpacing.md))
            ClearAirTextAction(text = "Receive", onClick = onReceive)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ClearAirSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ClearAirEyebrow(text = "Watchlist · ${rows.size}")
            ClearAirTextAction(text = "${sort.label} ↓", onClick = onSort)
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
    rows.forEach { row ->
        ClearAirRow(
            title = row.name,
            description = row.detail,
            trailing = { WatchlistPrice(row = row, testnet = testnet) },
        )
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
