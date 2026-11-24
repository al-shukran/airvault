package app.lumio.ui.home

import androidx.compose.runtime.Composable
import app.lumio.ui.kit.ChoiceGroup
import app.lumio.ui.kit.ChoiceRow
import app.lumio.ui.kit.ClearAirChoiceSheet
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.watchlist.WatchlistSort

/**
 * The sort sheet of the watchlist: two radio rows, one per order. A choice applies at once and closes the sheet. [selected] is the order the list
 * shows now.
 */
@Composable
fun WatchlistSortSheet(visible: Boolean, selected: WatchlistSort, onSelect: (WatchlistSort) -> Unit, onClose: () -> Unit) {
    ClearAirSheet(visible = visible, onClose = onClose) {
        ClearAirChoiceSheet(
            title = "Sort Watchlist",
            groups = listOf(
                ChoiceGroup(
                    label = null,
                    rows = WatchlistSort.entries.map { sort ->
                        ChoiceRow(title = sort.label, detail = sort.firstFirst, selected = sort == selected, onPick = { onSelect(sort) })
                    },
                ),
            ),
        )
    }
}

/** What comes first in an order, as the sheet says it: the largest market cap, or the biggest gain. */
private val WatchlistSort.firstFirst: String
    get() = when (this) {
        WatchlistSort.MARKET_CAP -> "Largest first"
        WatchlistSort.CHANGE_24H -> "Biggest gain first"
    }
