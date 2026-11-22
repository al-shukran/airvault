package app.lumio.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.watchlist.WatchlistSort

/**
 * The sort sheet of the watchlist: two radio rows, one per order. A choice applies at once and closes the sheet. [selected] is the order the list
 * shows now.
 */
@Composable
fun WatchlistSortSheet(visible: Boolean, selected: WatchlistSort, onSelect: (WatchlistSort) -> Unit, onClose: () -> Unit) {
    val palette = LocalClearAirPalette.current
    ClearAirSheet(visible = visible, onClose = onClose) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md)) {
            Text(text = "Sort Watchlist", style = ClearAirType.titleL, color = palette.ink)
            Spacer(Modifier.height(ClearAirSpacing.sm))
            WatchlistSort.entries.forEach { sort ->
                SortRow(sort = sort, selected = sort == selected, onClick = { onSelect(sort) })
            }
        }
    }
}

/** One order as a radio row: its label, what comes first in it, and the radio on the right. */
@Composable
private fun SortRow(sort: WatchlistSort, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ClearAirSpacing.touchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = sort.label, style = ClearAirType.rowTitle, color = palette.ink)
            Text(text = sort.firstFirst, style = ClearAirType.body, color = palette.ink2)
        }
        Spacer(Modifier.size(ClearAirSpacing.md))
        Radio(selected = selected)
    }
}

/** A 24 dp radio: a ring, with a filled dot in its centre when it is the choice. */
@Composable
private fun Radio(selected: Boolean) {
    val palette = LocalClearAirPalette.current
    Box(modifier = Modifier.size(24.dp).border(1.6.dp, palette.ink2, CircleShape), contentAlignment = Alignment.Center) {
        if (selected) Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(palette.ink))
    }
}

/** What comes first in an order, as the sheet says it: the largest market cap, or the biggest gain. */
private val WatchlistSort.firstFirst: String
    get() = when (this) {
        WatchlistSort.MARKET_CAP -> "Largest first"
        WatchlistSort.CHANGE_24H -> "Biggest gain first"
    }
