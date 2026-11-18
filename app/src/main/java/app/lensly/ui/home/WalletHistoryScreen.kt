package app.lensly.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.app.AddressBookEntry
import app.lensly.app.AddressChain
import app.lensly.app.InternalAccount
import app.lensly.app.VisualIdentity
import app.lensly.app.addressNotesOf
import app.lensly.app.canonicalAddressOn
import app.lensly.app.sign.BitcoinRecordFacts
import app.lensly.app.sign.NoticeLevel
import app.lensly.app.sign.ReviewKind
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.ReviewRecord
import app.lensly.app.sign.recordChainOf
import app.lensly.core.registry.NetworkEntry
import app.lensly.ui.components.BitcoinIcon
import app.lensly.ui.components.ChainIcon
import app.lensly.ui.components.DetailTopBar
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.profile.ProfileAvatar
import app.lensly.ui.sign.FactRow
import app.lensly.ui.sign.MonoText
import app.lensly.ui.sign.MutedText
import app.lensly.ui.sign.ReviewAddress
import app.lensly.ui.sign.btcText
import app.lensly.ui.sign.groupHex
import app.lensly.ui.theme.LenslyError
import app.lensly.ui.theme.LenslyMuted
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySuccess
import app.lensly.ui.theme.SpaceMonoFamily
import app.lensly.ui.wallet.DotText
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletScaffold
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private const val EMPTY_BODY =
    "Each request you review on this wallet is listed here, with what you decided. The list stays on this device."
private const val REQUEST_LABEL = "Request"
private const val BITCOIN_LABEL = "Bitcoin transaction"

/**
 * The Review Records of one wallet, newest first, under the day each was made. A row shows the outcome, the network, what the
 * request did, its amount and the time. A tap opens every recorded fact, with each address in full. With no record yet, the screen
 * says what will be listed here. [networkOf] names the network of a chain, for its logo.
 *
 * [addressBook] is read for the notes of each address the sheet shows. A tap on an address calls [onOpenAddress] with the address and its
 * chain, so the Address Book can open its entry, or a new one for it.
 */
@Composable
fun WalletHistoryScreen(
    walletName: String,
    records: List<ReviewRecord>,
    networkOf: (Long) -> NetworkEntry?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    addressBook: List<AddressBookEntry> = emptyList(),
    accounts: Map<String, InternalAccount> = emptyMap(),
    onOpenAddress: ((String, AddressChain) -> Unit)? = null,
) {
    val haptics = rememberKeyHaptics()
    var open by remember { mutableStateOf<ReviewRecord?>(null) }
    WalletScaffold(
        modifier = modifier,
        scrollable = false,
        topBar = { DetailTopBar(title = "History", onBack = onBack) },
    ) {
        Spacer(Modifier.height(LenslySpacing.lg))
        DotText(text = walletName.uppercase(), fontSize = 40.sp)
        Spacer(Modifier.height(LenslySpacing.sm))
        if (records.isEmpty()) {
            EmptyState(icon = LenslyIcons.History, title = "No reviews yet", body = EMPTY_BODY)
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                records.groupBy { dayOf(it.atMs) }.forEach { (day, dayRecords) ->
                    item(key = day.toString()) { DayHeader(day) }
                    items(dayRecords) { record ->
                        HistoryRow(record = record, network = record.chainId?.let(networkOf), onClick = { open = record })
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

@Composable
internal fun DayHeader(day: LocalDate) {
    WalletEyebrow(
        text = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
        modifier = Modifier.padding(top = LenslySpacing.md, bottom = LenslySpacing.xs),
    )
}

/**
 * One record: its outcome, its network, what the request did with its amount, and the time. A row on the all-wallet Activity
 * screen also names its wallet, through [walletName], with that wallet's Visual Identity, through [walletIdentity].
 */
@Composable
internal fun HistoryRow(
    record: ReviewRecord,
    network: NetworkEntry?,
    onClick: () -> Unit,
    walletName: String? = null,
    walletIdentity: VisualIdentity? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = LenslySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = outcomeIcon(record.outcome),
            contentDescription = outcomeLabel(record.outcome),
            tint = outcomeTint(record.outcome),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(LenslySpacing.sm))
        if (record.kind == ReviewKind.BITCOIN_TRANSACTION) {
            BitcoinIcon(size = 24.dp)
        } else {
            ChainIcon(network = network, size = 24.dp)
        }
        Spacer(Modifier.width(LenslySpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.function ?: record.refusal ?: requestLabel(record),
                style = MaterialTheme.typography.titleSmall,
                color = LenslyOnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (walletName == null) {
                Text(
                    text = timeOf(record.atMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    walletIdentity?.let { identity ->
                        ProfileAvatar(identity = identity, size = 14.dp)
                        Spacer(Modifier.width(LenslySpacing.xxs))
                    }
                    Text(
                        text = walletName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(LenslySpacing.xxs))
                    Text(
                        text = "· ${timeOf(record.atMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        record.amount?.let { amount ->
            Spacer(Modifier.width(LenslySpacing.sm))
            Text(
                text = amount,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceMonoFamily),
                color = LenslyOnSurface,
                textAlign = TextAlign.End,
            )
        }
    }
}

/**
 * Every fact the record holds. Each address it shows is a list row: a tap closes the sheet, then calls [onOpenAddress] with the address
 * and its chain. Each row shows what the Address Book holds for the address, from [addressBook]: its name, label and notes, or "Not in
 * address book". The sheet scrolls, since a message or a long call can be tall.
 */
@Composable
internal fun HistoryDetailSheet(
    record: ReviewRecord,
    addressBook: List<AddressBookEntry>,
    accounts: Map<String, InternalAccount> = emptyMap(),
    onDismiss: () -> Unit,
    onOpenAddress: ((String, AddressChain) -> Unit)? = null,
) {
    val chain = recordChainOf(record)
    val open = tapOf(chain, onOpenAddress, onDismiss)
    OptionsSheet(onDismiss = onDismiss, scrollable = true) {
        Text(
            text = outcomeLabel(record.outcome),
            style = MaterialTheme.typography.titleMedium,
            color = outcomeTint(record.outcome),
        )
        Spacer(Modifier.height(LenslySpacing.xxs))
        MutedText(dateTimeOf(record.atMs))
        Spacer(Modifier.height(LenslySpacing.sm))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        FactRow("Network") { FactText(networkLine(record)) }
        record.address?.let { address ->
            AddressFact(
                label = if (record.accountIndex == null) "Account" else "Account ${record.accountIndex}",
                value = address,
                chain = chain,
                book = addressBook,
                accounts = accounts,
                open = open,
            )
        }
        FactRow("Kind") { FactText(kindLabel(record.kind)) }
        record.bitcoin?.let { facts -> BitcoinFacts(record, facts, addressBook, accounts, open) }
        record.function?.let { function -> FactRow("Function") { FactText(function) } }
        record.to?.let { to -> AddressFact(label = "To", value = to, chain = chain, book = addressBook, accounts = accounts, open = open) }
        record.amount?.let { amount -> FactRow("Amount") { MonoText(amount) } }
        record.tokenContract?.let { contract ->
            AddressFact(label = "Token contract", value = contract, chain = chain, book = addressBook, accounts = accounts, open = open)
        }
        if (record.fee.isNotEmpty()) FactRow("Fee") { LabelledRows(record.fee, chain, addressBook, accounts, open) }
        if (record.params.isNotEmpty()) FactRow("Details") { LabelledRows(record.params, chain, addressBook, accounts, open) }
        record.origin?.let { origin -> FactRow("Origin") { FactText(origin) } }
        record.refusal?.let { reason -> FactRow("Refusal reason") { FactText(reason) } }
        FactRow("Data hash, SHA-256") { MonoText(groupHex(record.dataHash)) }
    }
}

/**
 * The tap of an address row: it closes the sheet, then opens the address on [chain]. Null when there is no action or no chain, so the
 * addresses are shown without a tap.
 */
private fun tapOf(
    chain: AddressChain?,
    onOpenAddress: ((String, AddressChain) -> Unit)?,
    onDismiss: () -> Unit,
): ((String) -> Unit)? {
    if (chain == null || onOpenAddress == null) return null
    return { address ->
        onDismiss()
        onOpenAddress(address, chain)
    }
}

/** Whether [value] is an address on [chain], so it is shown as an address row. */
private fun isAddressOn(value: String, chain: AddressChain?): Boolean = chain != null && canonicalAddressOn(value, chain) != null

/** A fact whose value is an address on [chain] is an address row. Any other value is text. */
@Composable
private fun AddressFact(
    label: String,
    value: String,
    chain: AddressChain?,
    book: List<AddressBookEntry>,
    accounts: Map<String, InternalAccount>,
    open: ((String) -> Unit)?,
) {
    FactRow(label) {
        if (chain != null && isAddressOn(value, chain)) {
            AddressBlock(address = value, chain = chain, book = book, accounts = accounts, open = open)
        } else {
            FactText(value)
        }
    }
}

/**
 * One address as a [ReviewAddress] row, with what the vault's [accounts] and the Address Book hold for it on [chain]. A tap opens it when
 * [open] is set.
 */
@Composable
private fun AddressBlock(
    address: String,
    chain: AddressChain,
    book: List<AddressBookEntry>,
    accounts: Map<String, InternalAccount>,
    open: ((String) -> Unit)?,
) {
    ReviewAddress(
        address = address,
        notes = addressNotesOf(address, chain, accounts, book),
        bitcoin = chain == AddressChain.BITCOIN,
        onClick = open?.let { tap -> { tap(address) } },
    )
}

@Composable
private fun FactText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = LenslyOnSurface)
}

/**
 * What a Bitcoin review showed: the account and its Script Type, each output with its address and amount, the fee and its rate, and every
 * notice the review gave, as a warning or as the reason it was blocked. Each output address is an address row, and so is a change output.
 */
@Composable
private fun BitcoinFacts(
    record: ReviewRecord,
    facts: BitcoinRecordFacts,
    book: List<AddressBookEntry>,
    accounts: Map<String, InternalAccount>,
    open: ((String) -> Unit)?,
) {
    record.accountIndex?.let { index -> FactRow("Account $index") { FactText(facts.scriptType) } }
    facts.outputs.forEachIndexed { index, output ->
        FactRow("Output $index") {
            val address = output.address
            if (address != null) {
                AddressBlock(address = address, chain = AddressChain.BITCOIN, book = book, accounts = accounts, open = open)
            } else {
                MutedText("No address. The script is not paid to an address.")
            }
            MonoText(btcText(output.amountSats))
            if (output.isOwnChange) MutedText("Your change")
        }
    }
    FactRow("Fee") {
        MonoText(facts.feeSats?.let { "$it sat (${btcText(it)})" } ?: "Unknown")
    }
    FactRow("Fee rate") {
        MonoText(facts.feeRateSatPerVb?.let { String.format(Locale.ROOT, "%.1f sat/vB", it) } ?: "Unknown")
    }
    facts.notices.forEach { notice ->
        FactRow(if (notice.level == NoticeLevel.BLOCK) "Blocked" else "Warning") { FactText(notice.text) }
    }
}

/**
 * Label and value rows, as the review showed them. A value that is an address on [chain] is an address row, and any other value is in the
 * monospace face, so each character can be compared by eye.
 */
@Composable
private fun LabelledRows(
    rows: List<Pair<String, String>>,
    chain: AddressChain?,
    book: List<AddressBookEntry>,
    accounts: Map<String, InternalAccount>,
    open: ((String) -> Unit)?,
) {
    rows.forEachIndexed { index, (label, value) ->
        if (index > 0) Spacer(Modifier.height(LenslySpacing.sm))
        MutedText(label)
        if (isAddressOn(value, chain)) {
            AddressBlock(address = value, chain = requireNotNull(chain), book = book, accounts = accounts, open = open)
        } else {
            MonoText(value)
        }
    }
}

/** The icon of an outcome, shared with the Home activity rows. */
internal fun outcomeIcon(outcome: ReviewOutcome): ImageVector = when (outcome) {
    ReviewOutcome.SIGNED -> Icons.Filled.Check
    ReviewOutcome.REFUSED -> Icons.Filled.Warning
    ReviewOutcome.CANCELLED -> Icons.Filled.Close
}

/** The colour of an outcome, shared with the Home activity rows. */
internal fun outcomeTint(outcome: ReviewOutcome): Color = when (outcome) {
    ReviewOutcome.SIGNED -> LenslySuccess
    ReviewOutcome.REFUSED -> LenslyError
    ReviewOutcome.CANCELLED -> LenslyMuted
}

/** The word for an outcome, shared with the Home activity rows. */
internal fun outcomeLabel(outcome: ReviewOutcome): String = when (outcome) {
    ReviewOutcome.SIGNED -> "Signed"
    ReviewOutcome.REFUSED -> "Refused"
    ReviewOutcome.CANCELLED -> "Cancelled"
}

/** The title of a record that named no function and was not refused: a Bitcoin transaction, or a request. */
private fun requestLabel(record: ReviewRecord): String =
    if (record.kind == ReviewKind.BITCOIN_TRANSACTION) BITCOIN_LABEL else REQUEST_LABEL

private fun kindLabel(kind: ReviewKind): String = when (kind) {
    ReviewKind.TRANSACTION -> "Transaction"
    ReviewKind.MESSAGE -> "Message"
    ReviewKind.BITCOIN_TRANSACTION -> BITCOIN_LABEL
}

private fun networkLine(record: ReviewRecord): String = when {
    record.networkName != null && record.chainId != null -> "${record.networkName} · chain ${record.chainId}"
    record.networkName != null -> record.networkName
    record.chainId != null -> "chain ${record.chainId}"
    else -> "The request named no network"
}

private fun zoned(atMs: Long) = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault())

internal fun dayOf(atMs: Long): LocalDate = zoned(atMs).toLocalDate()

// The formatters are made per call, so they follow the locale the screen is shown in, not the one the app started in.
private fun timeOf(atMs: Long): String = zoned(atMs).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

private fun dateTimeOf(atMs: Long): String =
    zoned(atMs).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
