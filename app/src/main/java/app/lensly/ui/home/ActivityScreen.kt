package app.lensly.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.app.WalletReviewRecord
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.ReviewRecord
import app.lensly.app.visualIdentity
import app.lensly.core.registry.NetworkEntry
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutline
import app.lensly.ui.theme.LenslyPillShape
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.DotText
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletScaffold

private const val EMPTY_TITLE = "No activity yet"
private const val EMPTY_BODY =
    "Each request you review on any wallet is listed here, with what you decided. The list stays on this device."
private const val FILTERED_TITLE = "Nothing here yet"
private const val FILTERED_BODY = "No review matches this filter. Choose another one."

/** The filters above the list: every record, or only the records of one outcome. */
internal enum class ActivityFilter(val label: String, val outcome: ReviewOutcome?) {
    ALL("All", null),
    SIGNED("Signed", ReviewOutcome.SIGNED),
    REFUSED("Refused", ReviewOutcome.REFUSED),
    CANCELLED("Cancelled", ReviewOutcome.CANCELLED),
}

/**
 * The Review Records of every wallet in the open session, newest first, under the day each was made. Each row is the row of the
 * wallet's own History, and also names the wallet it belongs to, with that wallet's Visual Identity. The filter chips above the
 * list keep one outcome. A tap opens every recorded fact, in the same sheet the wallet's History opens. With no record yet, the
 * screen says what will be listed here.
 *
 * [wallets] is the session's wallets in their usual order, which sets the generated look of a wallet with no profile of its own.
 * [networkOf] names the network of a chain, for its logo.
 */
@Composable
fun ActivityScreen(
    records: List<WalletReviewRecord>,
    wallets: List<WalletMeta>,
    profiles: Map<String, WalletProfile>,
    networkOf: (Long) -> NetworkEntry?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberKeyHaptics()
    var filter by remember { mutableStateOf(ActivityFilter.ALL) }
    var open by remember { mutableStateOf<ReviewRecord?>(null) }
    val walletById = remember(wallets) { wallets.associateBy { it.id } }
    val positionById = remember(wallets) { wallets.mapIndexed { index, wallet -> wallet.id to index }.toMap() }
    val shown = records
        .filter { filter.outcome == null || it.record.outcome == filter.outcome }
        .sortedByDescending { it.record.atMs }
    WalletScaffold(modifier = modifier, scrollable = false) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.xs)) {
            IconButton(onClick = onBack, modifier = Modifier.tapHaptic(haptics)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = LenslyOnSurface)
            }
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        WalletEyebrow("Reviews")
        Spacer(Modifier.height(LenslySpacing.xs))
        DotText(text = "ACTIVITY", fontSize = 40.sp)
        Spacer(Modifier.height(LenslySpacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
        ) {
            ActivityFilter.entries.forEach { option ->
                FilterChip(option = option, chosen = option == filter, onClick = { filter = option })
            }
        }
        Spacer(Modifier.height(LenslySpacing.sm))
        when {
            records.isEmpty() -> EmptyState(icon = LenslyIcons.History, title = EMPTY_TITLE, body = EMPTY_BODY)
            shown.isEmpty() -> EmptyState(icon = LenslyIcons.History, title = FILTERED_TITLE, body = FILTERED_BODY)
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                shown.withIndex().groupBy { dayOf(it.value.record.atMs) }.forEach { (day, dayRecords) ->
                    item(key = day.toString()) { DayHeader(day) }
                    // The position in the whole list is unique, so two records with equal facts cannot clash.
                    items(dayRecords, key = { it.index }) { position ->
                        val item = position.value
                        val wallet = walletById[item.walletId]
                        HistoryRow(
                            record = item.record,
                            network = item.record.chainId?.let(networkOf),
                            onClick = { open = item.record },
                            walletName = wallet?.name,
                            walletIdentity = wallet?.let { meta ->
                                val profile = profiles[meta.id] ?: WalletProfile()
                                visualIdentity(profile.colour, profile.icon, positionById[meta.id] ?: 0)
                            },
                        )
                    }
                }
            }
        }
    }
    open?.let { record ->
        HistoryDetailSheet(record = record, onDismiss = { open = null })
    }
}

/** One filter chip: filled while [chosen], an outline otherwise. */
@Composable
private fun FilterChip(option: ActivityFilter, chosen: Boolean, onClick: () -> Unit) {
    val shape = LenslyPillShape
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (chosen) LenslyOnSurface else Color.Transparent)
            .border(1.dp, if (chosen) LenslyOnSurface else LenslyOutline, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = LenslySpacing.sm, vertical = LenslySpacing.xxs)
            .semantics { selected = chosen },
    ) {
        Text(
            text = option.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = if (chosen) LenslyBlack else LenslyOnSurface,
        )
    }
}
