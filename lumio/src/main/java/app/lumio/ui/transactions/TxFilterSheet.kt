package app.lumio.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.ui.components.LenslySegmentedControl
import app.lensly.ui.components.ListRow
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WALLET_GUTTER
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillStyle
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxFilter
import app.lumio.data.tx.TxPeriod
import app.lumio.data.tx.TxSort
import app.lumio.transactions.TxAssetOption
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The test tag of the sheet's Done pill. */
const val TX_FILTER_DONE_TAG = "tx-filter-done"

/** The sheet of the Transactions tab's search filters and sort. Each choice applies as the owner makes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxFilterSheet(
    filter: TxFilter,
    assets: List<TxAssetOption>,
    onFilter: (TxFilter) -> Unit,
    onDismiss: () -> Unit,
    zone: ZoneId,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        TxFilterContent(filter = filter, assets = assets, onFilter = onFilter, onDone = onDismiss, zone = zone)
    }
}

/**
 * The content of the filter sheet: the period as a segmented control, with two calendar days when the period is a range; the assets, directions and
 * amount bounds as chips and fields; and the sort key and its order. Clear drops every filter but the search and the sort. Done closes the sheet.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TxFilterContent(
    filter: TxFilter,
    assets: List<TxAssetOption>,
    onFilter: (TxFilter) -> Unit,
    onDone: () -> Unit,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf<DayField?>(null) }
    var aboveText by remember { mutableStateOf(filter.amountAbove?.toPlainString().orEmpty()) }
    var belowText by remember { mutableStateOf(filter.amountBelow?.toPlainString().orEmpty()) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = WALLET_GUTTER, top = LenslySpacing.sm, end = WALLET_GUTTER, bottom = LenslySpacing.md),
    ) {
        WalletEyebrow("Period")
        Spacer(Modifier.height(LenslySpacing.xs))
        LenslySegmentedControl(
            options = TxPeriod.entries,
            selected = filter.period,
            label = ::periodLabel,
            onSelect = { onFilter(filter.copy(period = it)) },
        )
        if (filter.period == TxPeriod.DAYS) {
            DayRow(label = "From", day = filter.fromDay, zone = zone, onPick = { picking = DayField.FROM })
            DayRow(label = "Until", day = filter.untilDay, zone = zone, onPick = { picking = DayField.UNTIL })
        }

        Spacer(Modifier.height(LenslySpacing.md))
        WalletEyebrow("Asset")
        Spacer(Modifier.height(LenslySpacing.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs), verticalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
            assets.forEach { option ->
                FilterChip(
                    selected = option.ref in filter.assets,
                    onClick = { onFilter(filter.copy(assets = filter.assets.toggle(option.ref))) },
                    label = { Text(chipLabel(option, assets)) },
                )
            }
        }

        Spacer(Modifier.height(LenslySpacing.md))
        WalletEyebrow("Direction")
        Spacer(Modifier.height(LenslySpacing.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs), verticalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
            TxDirection.entries.forEach { direction ->
                FilterChip(
                    selected = direction in filter.directions,
                    onClick = { onFilter(filter.copy(directions = filter.directions.toggle(direction))) },
                    label = { Text(directionChipLabel(direction)) },
                )
            }
        }

        Spacer(Modifier.height(LenslySpacing.md))
        WalletEyebrow("Amount in coins")
        Spacer(Modifier.height(LenslySpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm)) {
            OutlinedTextField(
                value = aboveText,
                onValueChange = {
                    aboveText = it
                    onFilter(filter.copy(amountAbove = parseAmount(it)))
                },
                label = { Text("More than") },
                placeholder = { Text("Any") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).testTag(TX_FILTER_ABOVE_TAG),
            )
            OutlinedTextField(
                value = belowText,
                onValueChange = {
                    belowText = it
                    onFilter(filter.copy(amountBelow = parseAmount(it)))
                },
                label = { Text("Less than") },
                placeholder = { Text("Any") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).testTag(TX_FILTER_BELOW_TAG),
            )
        }

        Spacer(Modifier.height(LenslySpacing.md))
        WalletEyebrow("Sort by")
        Spacer(Modifier.height(LenslySpacing.xs))
        TxSort.entries.forEach { key ->
            ListRow(onClick = { onFilter(filter.copy(sort = key)) }) {
                Text(
                    text = sortLabel(key),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = LenslyOnSurface,
                )
                if (key == filter.sort) {
                    Icon(imageVector = Icons.Filled.Check, contentDescription = "Selected", tint = LenslyOnSurface)
                }
            }
        }
        Spacer(Modifier.height(LenslySpacing.md))
        WalletEyebrow("Order")
        Spacer(Modifier.height(LenslySpacing.xs))
        LenslySegmentedControl(
            options = listOf(true, false),
            selected = filter.descending,
            label = { if (it) "Descending" else "Ascending" },
            onSelect = { onFilter(filter.copy(descending = it)) },
        )

        Spacer(Modifier.height(LenslySpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm)) {
            WalletPill(
                text = "Clear",
                onClick = {
                    aboveText = ""
                    belowText = ""
                    onFilter(TxFilter(text = filter.text, sort = filter.sort, descending = filter.descending))
                },
                modifier = Modifier.weight(1f).testTag(TX_FILTER_CLEAR_TAG),
                style = WalletPillStyle.Secondary,
            )
            WalletPill(
                text = "Done",
                onClick = onDone,
                modifier = Modifier.weight(1f).testTag(TX_FILTER_DONE_TAG),
                style = WalletPillStyle.Primary,
            )
        }
    }

    picking?.let { field ->
        val current = if (field == DayField.FROM) filter.fromDay else filter.untilDay
        DayPickerDialog(
            initial = current ?: LocalDate.now(zone),
            onPick = { day ->
                onFilter(if (field == DayField.FROM) filter.copy(fromDay = day) else filter.copy(untilDay = day))
            },
            onDismiss = { picking = null },
        )
    }
}

/** Which of the two days of a range the picker is set for. */
private enum class DayField { FROM, UNTIL }

/** The test tags of the amount fields and of the Clear pill. */
const val TX_FILTER_ABOVE_TAG = "tx-filter-above"
const val TX_FILTER_BELOW_TAG = "tx-filter-below"
const val TX_FILTER_CLEAR_TAG = "tx-filter-clear"

/** One calendar day of a range, shown as a row that opens the date picker when it is tapped. */
@Composable
private fun DayRow(label: String, day: LocalDate?, zone: ZoneId, onPick: () -> Unit) {
    ListRow(onClick = onPick) {
        Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = LenslyOnSurface)
        Text(
            text = day?.let { dayText(it) } ?: "Pick a day",
            style = MaterialTheme.typography.bodyMedium,
            color = LenslyOnSurface,
        )
    }
}

/** The picker of one day. It works in UTC, the way the platform picker reports a day, and the day that it returns is the calendar day picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPickerDialog(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    onDismiss()
                },
            ) {
                Text("OK")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}

/** The label of a period, as the segmented control shows it. */
fun periodLabel(period: TxPeriod): String = when (period) {
    TxPeriod.ANY -> "Any"
    TxPeriod.LAST_24H -> "24h"
    TxPeriod.LAST_7D -> "7d"
    TxPeriod.LAST_30D -> "30d"
    TxPeriod.DAYS -> "Dates"
}

/** The label of a direction chip. */
fun directionChipLabel(direction: TxDirection): String = when (direction) {
    TxDirection.IN -> "Received"
    TxDirection.OUT -> "Sent"
    TxDirection.SELF -> "Self"
}

/** The label of a sort key as the owner reads it. */
fun sortLabel(sort: TxSort): String = when (sort) {
    TxSort.TIMESTAMP -> "Date"
    TxSort.AMOUNT -> "Amount"
    TxSort.FEE -> "Fee"
    TxSort.ASSET -> "Asset"
    TxSort.DIRECTION -> "Direction"
    TxSort.STATUS -> "Status"
    TxSort.NETWORK -> "Network"
}

/**
 * The sort as the Transactions tab's header reads it, such as "Date, newest first" or "Amount, high to low". The words follow the key: a date reads as
 * newest or oldest, a number as high or low, and a name as A to Z or Z to A.
 */
fun sortLine(sort: TxSort, descending: Boolean): String {
    val order = when (sort) {
        TxSort.TIMESTAMP -> if (descending) "newest first" else "oldest first"
        TxSort.AMOUNT, TxSort.FEE -> if (descending) "high to low" else "low to high"
        TxSort.ASSET, TxSort.DIRECTION, TxSort.STATUS, TxSort.NETWORK -> if (descending) "Z to A" else "A to Z"
    }
    return "${sortLabel(sort)}, $order"
}

/** The label of an asset chip. An asset whose symbol another chain also has shows its chain beside it. */
private fun chipLabel(option: TxAssetOption, all: List<TxAssetOption>): String =
    if (all.count { it.symbol == option.symbol } > 1) "${option.symbol} on ${option.chain}" else option.symbol

/** [text] as an amount in coins, or null when it is blank, not a number, or negative. */
internal fun parseAmount(text: String): BigDecimal? = text.trim().toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }

/** [this] with [item] added when it is absent, and removed when it is present. */
private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item

/** A day as the rows of the sheet show it, such as "1 Nov 2026". */
private fun dayText(day: LocalDate): String = day.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
