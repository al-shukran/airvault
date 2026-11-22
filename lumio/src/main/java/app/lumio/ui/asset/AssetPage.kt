package app.lumio.ui.asset

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.price.ChartRange
import app.lumio.data.price.PriceSeries
import app.lumio.ui.kit.ClearAirCapsuleSmall
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirIconButton
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirTextAction

/** A coin's holdings across the wallet: the amount, the accounts and chains it sits on, and its value in dollars when a price is read. */
internal data class CoinHeld(val amount: String, val summary: String, val value: String?)

/** A price's change over the chart's window: its text, and whether it rose. */
internal data class PriceChange(val text: String, val rising: Boolean)

/**
 * The Asset page (C4): one coin across the wallet, pushed from Home, Search and the watchlist. The top bar holds the back arrow, the coin's [name],
 * the watchlist star and the lock. Under it come the price and its change, the tick chart, the range row, Send and Receive, the holdings row that
 * opens Holdings Breakdown, and the coin's activity. [held] is null for a coin nobody holds: the page then drops the holdings row and makes Receive
 * the capsule, as the Account page does for a zero balance. A missing [priceText] reads as a dash, because no price is read yet.
 */
@Composable
internal fun AssetPage(
    name: String,
    symbol: String,
    icon: @Composable () -> Unit,
    priceText: String?,
    change: PriceChange?,
    series: PriceSeries?,
    range: ChartRange,
    onRange: (ChartRange) -> Unit,
    watched: Boolean,
    onWatch: () -> Unit,
    held: CoinHeld?,
    onHoldings: () -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onAllActivity: () -> Unit,
    activity: @Composable () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = ClearAirSpacing.md)) {
        AssetTopBar(name = name, watched = watched, onWatch = onWatch, onBack = onBack, onLock = onLock)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            icon()
            Spacer(Modifier.height(ClearAirSpacing.xs))
            ClearAirEyebrow(text = "$name · $symbol")
            Text(text = priceText ?: "—", style = ClearAirType.hero, color = palette.ink)
            change?.let { value ->
                Text(
                    text = value.text,
                    style = ClearAirType.body,
                    color = if (value.rising) palette.vaultBlue else palette.rose,
                )
            }
        }
        TickChart(series = series ?: PriceSeries(emptyList()), modifier = Modifier.padding(top = ClearAirSpacing.md))
        RangeRow(selected = range, onSelect = onRange, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (held == null) {
                ClearAirCapsuleSmall(text = "Receive", onClick = onReceive)
                Spacer(Modifier.width(ClearAirSpacing.md))
                ClearAirTextAction(text = "Send $symbol", onClick = onSend)
            } else {
                ClearAirCapsuleSmall(text = "Send $symbol", onClick = onSend)
                Spacer(Modifier.width(ClearAirSpacing.md))
                ClearAirTextAction(text = "Receive", onClick = onReceive)
            }
        }
        held?.let { coin ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter)) {
                ClearAirEyebrow(text = "Your $symbol")
            }
            HeldRow(coin = coin, icon = icon, onClick = onHoldings)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ClearAirEyebrow(text = "Activity")
            ClearAirTextAction(text = "All", onClick = onAllActivity)
        }
        activity()
    }
}

/** The top bar of the Asset page: back, the coin's name, the watchlist star and the lock. */
@Composable
private fun AssetTopBar(name: String, watched: Boolean, onWatch: () -> Unit, onBack: () -> Unit, onLock: () -> Unit) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClearAirIconButton(glyph = ClearAirGlyph.BACK, label = "Back", onClick = onBack)
        Text(
            text = name,
            modifier = Modifier.weight(1f).padding(horizontal = ClearAirSpacing.xs),
            style = ClearAirType.rowTitle,
            color = palette.ink,
        )
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(role = Role.Checkbox, onClick = onWatch)
                .semantics {
                    contentDescription = if (watched) "Remove $name from the watchlist" else "Add $name to the watchlist"
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = if (watched) "★" else "☆", style = ClearAirType.titleL, color = palette.ink)
        }
        ClearAirIconButton(glyph = ClearAirGlyph.LOCK, label = "Lock", onClick = onLock)
    }
}

/** The coin's holdings row, which opens Holdings Breakdown. Its value sits on the right when a price is read. */
@Composable
private fun HeldRow(coin: CoinHeld, icon: @Composable () -> Unit, onClick: () -> Unit) {
    val palette = LocalClearAirPalette.current
    val value = coin.value
    if (value == null) {
        ClearAirRow(title = coin.amount, description = coin.summary, leading = icon, onClick = onClick)
    } else {
        ClearAirRow(
            title = coin.amount,
            description = coin.summary,
            leading = icon,
            trailing = { Text(text = value, style = ClearAirType.rowValue, color = palette.ink) },
            onClick = onClick,
        )
    }
}

/** The chart ranges as mono text. The chosen one carries the diamond, and tapping one chooses it. */
@Composable
private fun RangeRow(selected: ChartRange, onSelect: (ChartRange) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        ChartRange.entries.forEach { range ->
            val chosen = range == selected
            Text(
                text = if (chosen) "◆${range.label}" else range.label,
                modifier = Modifier.clickable(role = Role.Tab, onClick = { onSelect(range) }).padding(vertical = ClearAirSpacing.xxs),
                style = ClearAirType.address,
                color = if (chosen) palette.ink else palette.ink2,
            )
        }
    }
}
