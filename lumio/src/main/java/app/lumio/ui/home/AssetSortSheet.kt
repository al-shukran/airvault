package app.lumio.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.lensly.ui.components.LenslySegmentedControl
import app.lensly.ui.components.ListRow
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WALLET_GUTTER
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillStyle
import app.lumio.data.config.AssetSort
import app.lumio.data.config.AssetSortKey

/** The test tag of the sheet's Done pill. */
const val SORT_DONE_TAG = "sort-done"

/** The sheet that sets the key the asset list is sorted by, and its direction. It saves each choice as the owner makes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetSortSheet(sort: AssetSort, onSort: (AssetSort) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        AssetSortSheetContent(sort = sort, onSort = onSort, onDone = onDismiss)
    }
}

/**
 * The content of the sort sheet: the sort keys as a list, with the one in use checked, and the direction as a segmented control. A tap on a key or a
 * segment calls [onSort] with the new sort. Done closes the sheet.
 */
@Composable
fun AssetSortSheetContent(sort: AssetSort, onSort: (AssetSort) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = WALLET_GUTTER, top = LenslySpacing.sm, end = WALLET_GUTTER, bottom = LenslySpacing.md),
    ) {
        WalletEyebrow("Sort assets by")
        Spacer(Modifier.height(LenslySpacing.xs))
        AssetSortKey.entries.forEach { key ->
            ListRow(onClick = { onSort(sort.copy(key = key)) }) {
                Text(
                    text = sortKeyLabel(key),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = LenslyOnSurface,
                )
                if (key == sort.key) {
                    Icon(imageVector = Icons.Filled.Check, contentDescription = "Selected", tint = LenslyOnSurface)
                }
            }
        }
        Spacer(Modifier.height(LenslySpacing.md))
        WalletEyebrow("Order")
        Spacer(Modifier.height(LenslySpacing.xs))
        LenslySegmentedControl(
            options = listOf(false, true),
            selected = sort.descending,
            label = { if (it) "Descending" else "Ascending" },
            onSelect = { descending -> onSort(sort.copy(descending = descending)) },
        )
        Spacer(Modifier.height(LenslySpacing.md))
        WalletPill(
            text = "Done",
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().testTag(SORT_DONE_TAG),
            style = WalletPillStyle.Primary,
        )
    }
}

/** The name of a sort key as the owner reads it. */
fun sortKeyLabel(key: AssetSortKey): String = when (key) {
    AssetSortKey.USD_VALUE -> "USD value"
    AssetSortKey.NAME -> "Name"
    AssetSortKey.BALANCE -> "Balance"
    AssetSortKey.CHANGE_24H -> "24h change"
}

/** The sort as the list header shows it, such as "USD value, high to low" or "Name, A to Z". */
fun sortLabel(sort: AssetSort): String {
    val direction = when (sort.key) {
        AssetSortKey.NAME -> if (sort.descending) "Z to A" else "A to Z"
        else -> if (sort.descending) "high to low" else "low to high"
    }
    return "${sortKeyLabel(sort.key)}, $direction"
}
