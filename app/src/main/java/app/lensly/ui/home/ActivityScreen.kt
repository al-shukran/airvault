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
import app.airvault.core.model.ChainId
import app.lensly.app.AddressBookEntry
import app.lensly.app.InternalAccount
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.app.WalletReviewRecord
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.ReviewRecord
import app.lensly.app.visualIdentity
import app.lensly.core.registry.NetworkEntry
import app.lensly.ui.components.DetailTopBar
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.theme.LenslyPillShape
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletScaffold

private const val EMPTY_TITLE = "No Activity Yet"
private const val EMPTY_BODY =
    "Each request you review on any wallet is listed here, with what you decided. The list stays on this device."
private const val FILTERED_TITLE = "Nothing Here Yet"
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
 * [networks] is the registry, which gives the logo of each network a row's record names.
 */
@Composable
fun ActivityScreen(
    records: List<WalletReviewRecord>,
    wallets: List<WalletMeta>,
    profiles: Map<String, WalletProfile>,
    networks: List<NetworkEntry>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The Address Book, read for the notes of each address a record's sheet shows. */
    addressBook: List<AddressBookEntry> = emptyList(),
    /** The accounts of every wallet, read for the name of each address of this device that a record's sheet shows. */
    accounts: Map<String, InternalAccount> = emptyMap(),
    /** Opens the Address Book for an address a record's sheet shows, on its chain. Null shows the addresses without a tap. */
    onOpenAddress: ((String, ChainId) -> Unit)? = null,
) {
    val haptics = rememberKeyHaptics()
    var filter by remember { mutableStateOf(ActivityFilter.ALL) }
    var open by remember { mutableStateOf<ReviewRecord?>(null) }
    val walletById = remember(wallets) { wallets.associateBy { it.id } }
    val positionById = remember(wallets) { wallets.mapIndexed { index, wallet -> wallet.id to index }.toMap() }
    val shown = records
        .filter { filter.outcome == null || it.record.outcome == filter.outcome }
        .sortedByDescending { it.record.atMs }
    WalletScaffold(
        modifier = modifier,
        scrollable = false,
        topBar = { DetailTopBar(title = "Activity", onBack = onBack) },
    ) {
        Spacer(Modifier.height(LenslySpacing.lg))
        WalletEyebrow("Reviews")
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
                            networks = networks,
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
        HistoryDetailSheet(
            record = record,
            addressBook = addressBook,
            accounts = accounts,
            onDismiss = { open = null },
            onOpenAddress = onOpenAddress,
        )
    }
}

/** One filter chip: filled while [chosen], an outline otherwise. */
@Composable
private fun FilterChip(option: ActivityFilter, chosen: Boolean, onClick: () -> Unit) {
    val shape = LenslyPillShape
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (chosen) MaterialTheme.colorScheme.onSurface else Color.Transparent)
            .border(1.dp, if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = LenslySpacing.sm, vertical = LenslySpacing.xxs)
            .semantics { selected = chosen },
    ) {
        Text(
            text = option.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (chosen) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
        )
    }
}
