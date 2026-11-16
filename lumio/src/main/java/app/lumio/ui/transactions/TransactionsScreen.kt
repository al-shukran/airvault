package app.lumio.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxSize
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslyMuted
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WALLET_GUTTER
import app.lumio.data.tx.TxFilter
import app.lumio.transactions.TxAssetOption
import app.lumio.transactions.TxFeedItem
import java.time.ZoneId

/** The test tag of the search field. */
const val TX_SEARCH_TAG = "tx-search"

/** The test tag of the filter button. */
const val TX_FILTERS_TAG = "tx-filters"

/** The test tag of the button that loads the next page of the feed. */
const val TX_MORE_TAG = "tx-more"

/**
 * The Transactions tab: the consolidated feed of the side's transactions, with the search field, the count and the sort in use, and the button that opens
 * the filter sheet. Pull to refresh reads the providers again, and each row opens its transaction. [rows] are the first [rows] of the feed, and [count] is
 * how many the filter matches in all, so a button loads the next page when there are more.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    rows: List<TxFeedItem>,
    count: Int,
    filter: TxFilter,
    assets: List<TxAssetOption>,
    refreshing: Boolean,
    onFilter: (TxFilter) -> Unit,
    onMore: () -> Unit,
    onOpen: (TxFeedItem) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    LenslyScreen(title = "TRANSACTIONS", modifier = modifier, onBack = null, scrollable = false) {
        OutlinedTextField(
            value = filter.text,
            onValueChange = { onFilter(filter.copy(text = it)) },
            placeholder = { Text("Hash, address, memo or comment") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = WALLET_GUTTER).testTag(TX_SEARCH_TAG),
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = WALLET_GUTTER),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
        ) {
            Text(
                text = "${countText(count)} · ${sortLine(filter.sort, filter.descending)}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = LenslyMuted,
            )
            PillButton(
                text = filterButtonText(filter.activeCount),
                onClick = { sheetOpen = true },
                modifier = Modifier.testTag(TX_FILTERS_TAG),
            )
        }
        Spacer(Modifier.height(LenslySpacing.sm))
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            if (rows.isEmpty()) {
                FeedEmpty(filtered = filter.activeCount > 0 || filter.text.isNotBlank(), onClear = { onFilter(TxFilter(sort = filter.sort, descending = filter.descending)) })
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LenslySpacing.lg)) {
                    items(rows, key = { it.tx.account + "|" + it.tx.id }) { row ->
                        TxCard(item = row, zone = zone, onClick = { onOpen(row) })
                    }
                    if (rows.size < count) {
                        item(key = "more") {
                            PillButton(
                                text = "Show more",
                                onClick = onMore,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = WALLET_GUTTER).testTag(TX_MORE_TAG),
                            )
                        }
                    }
                }
            }
        }
    }
    if (sheetOpen) {
        TxFilterSheet(filter = filter, assets = assets, onFilter = onFilter, onDismiss = { sheetOpen = false }, zone = zone)
    }
}

/** The empty feed: nothing has been read yet, or nothing matches the search and the filters, with a way to clear them. */
@Composable
private fun FeedEmpty(filtered: Boolean, onClear: () -> Unit) {
    if (filtered) {
        EmptyState(
            icon = LenslyIcons.History,
            title = "Nothing matches",
            body = "Change the search or the filters.",
            actionLabel = "Clear filters",
            onAction = onClear,
        )
    } else {
        EmptyState(
            icon = LenslyIcons.History,
            title = "No transactions yet",
            body = "Transactions of your paired wallets appear here as they are read.",
        )
    }
}

/** The count line's first half, such as "1 transaction" or "42 transactions". */
internal fun countText(count: Int): String = if (count == 1) "1 transaction" else "$count transactions"

/** The filter button's label: "Filters" with no filter set, and "Filters (2)" with two. */
internal fun filterButtonText(active: Int): String = if (active == 0) "Filters" else "Filters ($active)"
