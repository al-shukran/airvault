package app.lensly.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.airvault.core.model.ChainId
import app.airvault.core.model.isPrintableCodePoint
import app.lensly.app.AddressBookEntry
import app.lensly.app.BITCOIN_FAMILY
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
import app.lensly.core.wallet.toHex
import app.lensly.ui.components.DetailTopBar
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.LumioIsland
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.profile.ProfileAvatar
import app.lensly.ui.sign.FactRow
import app.lensly.ui.sign.MonoText
import app.lensly.ui.sign.MutedText
import app.lensly.ui.sign.ChainNetworkLogo
import app.lensly.ui.sign.ReviewAddress
import app.airvault.chains.bitcoin.signer.btcText
import app.lensly.ui.sign.groupHex
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LocalLumenPalette
import app.lensly.ui.theme.LocalMonoFamily
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
private const val BITCOIN_LABEL = "Bitcoin Transaction"

/**
 * The Review Records of one wallet, newest first, under the day each was made. A row shows the outcome, the network, what the
 * request did, its amount and the time. A tap opens every recorded fact, with each address in full. With no record yet, the screen
 * says what will be listed here.
 *
 * [networks] is the registry. A row's logo is the logo of the network its record names, looked up there by name.
 * [addressBook] is read for the notes of each address the sheet shows. A tap on an address calls [onOpenAddress] with the address and its
 * chain, so the Address Book can open its entry, or a new one for it.
 */
@Composable
fun WalletHistoryScreen(
    walletName: String,
    records: List<ReviewRecord>,
    networks: List<NetworkEntry>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    addressBook: List<AddressBookEntry> = emptyList(),
    accounts: Map<String, InternalAccount> = emptyMap(),
    onOpenAddress: ((String, ChainId) -> Unit)? = null,
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
            EmptyState(icon = LenslyIcons.History, title = "No Reviews Yet", body = EMPTY_BODY)
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                records.groupBy { dayOf(it.atMs) }.forEach { (day, dayRecords) ->
                    item(key = day.toString()) { DayHeader(day) }
                    items(dayRecords) { record ->
                        HistoryRow(record = record, networks = networks, onClick = { open = record })
                    }
                }
            }
        }
    }
    open?.let { record ->
        HistoryDetailSheet(
            record = record,
            networks = networks,
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
 * One record: its outcome, its logo, what the request did with its amount, and the time. A row on the all-wallet Activity
 * screen also names its wallet, through [walletName], with that wallet's Visual Identity, through [walletIdentity].
 */
@Composable
internal fun HistoryRow(
    record: ReviewRecord,
    networks: List<NetworkEntry>,
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
        ChainNetworkLogo(chain = recordChainOf(record), networkName = record.networkName, networks = networks, size = 24.dp)
        Spacer(Modifier.width(LenslySpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.function ?: record.refusal ?: requestLabel(record),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
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
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = LocalMonoFamily.current),
                color = MaterialTheme.colorScheme.onSurface,
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
    networks: List<NetworkEntry>,
    addressBook: List<AddressBookEntry>,
    accounts: Map<String, InternalAccount> = emptyMap(),
    onDismiss: () -> Unit,
    onOpenAddress: ((String, ChainId) -> Unit)? = null,
) {
    val chain = recordChainOf(record)
    val open = tapOf(chain, onOpenAddress, onDismiss)
    // Each address of the record shows the logo of the network the record names, as its row does.
    val logo: @Composable () -> Unit = { ChainNetworkLogo(chain = chain, networkName = record.networkName, networks = networks, size = 16.dp) }
    OptionsSheet(onDismiss = onDismiss, scrollable = true) {
        OutcomeHero(record)
        Spacer(Modifier.height(LenslySpacing.lg))
        LumioIsland {
            Column(modifier = Modifier.padding(horizontal = LenslySpacing.md)) {
                FactRow("Network") { FactText(networkLine(record)) }
                record.address?.let { address ->
                    AddressFact(
                        label = if (record.accountIndex == null) "Account" else "Account ${record.accountIndex}",
                        value = address,
                        chain = chain,
                        book = addressBook,
                        accounts = accounts,
                        open = open,
                        logo = logo,
                    )
                }
                FactRow("Kind") { FactText(kindLabel(record.kind)) }
                record.bitcoin?.let { facts -> BitcoinFacts(record, facts, addressBook, accounts, open, logo) }
                record.function?.let { function -> FactRow("Function") { FactText(function) } }
                record.to?.let { to ->
                    AddressFact(label = "To", value = to, chain = chain, book = addressBook, accounts = accounts, open = open, logo = logo)
                }
                record.amount?.let { amount -> FactRow("Amount") { MonoText(amount) } }
                record.tokenContract?.let { contract ->
                    AddressFact(
                        label = "Token Contract",
                        value = contract,
                        chain = chain,
                        book = addressBook,
                        accounts = accounts,
                        open = open,
                        logo = logo,
                    )
                }
                if (record.fee.isNotEmpty()) FactRow("Fee") { LabelledRows(record.fee, chain, addressBook, accounts, open, logo) }
                if (record.params.isNotEmpty()) FactRow("Details") { LabelledRows(record.params, chain, addressBook, accounts, open, logo) }
                record.origin?.let { origin -> FactRow("Claimed by Request (Not Verified)") { FactText(historyOrigin(origin)) } }
                record.refusal?.let { reason -> FactRow("Refusal Reason") { FactText(reason) } }
                FactRow("Sign Data Hash (SHA-256)") { MonoText(groupHex(record.dataHash)) }
            }
        }
    }
}

/** The outcome of a record as the sheet hero: its icon in a 56 dp disc, its label, and when it was made. */
@Composable
private fun OutcomeHero(record: ReviewRecord) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = outcomeIcon(record.outcome),
                contentDescription = null,
                tint = outcomeTint(record.outcome),
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            text = outcomeLabel(record.outcome),
            style = MaterialTheme.typography.titleLarge,
            color = outcomeTint(record.outcome),
        )
        Spacer(Modifier.height(LenslySpacing.xxs))
        MutedText(dateTimeOf(record.atMs))
    }
}

/**
 * The tap of an address row: it closes the sheet, then opens the address on [chain]. Null when there is no action or no chain, so the
 * addresses are shown without a tap.
 */
private fun tapOf(
    chain: ChainId?,
    onOpenAddress: ((String, ChainId) -> Unit)?,
    onDismiss: () -> Unit,
): ((String) -> Unit)? {
    if (chain == null || onOpenAddress == null) return null
    return { address ->
        onDismiss()
        onOpenAddress(address, chain)
    }
}

/** Whether [value] is an address on [chain], so it is shown as an address row. */
private fun isAddressOn(value: String, chain: ChainId?): Boolean = chain != null && canonicalAddressOn(value, chain) != null

/** A fact whose value is an address on [chain] is an address row. Any other value is text. */
@Composable
private fun AddressFact(
    label: String,
    value: String,
    chain: ChainId?,
    book: List<AddressBookEntry>,
    accounts: Map<String, InternalAccount>,
    open: ((String) -> Unit)?,
    logo: @Composable () -> Unit,
) {
    FactRow(label) {
        if (chain != null && isAddressOn(value, chain)) {
            AddressBlock(address = value, chain = chain, book = book, accounts = accounts, open = open, logo = logo)
        } else {
            FactText(value)
        }
    }
}

/**
 * One address as a [ReviewAddress] row, with what the vault's [accounts] and the Address Book hold for it on [chain], and the [logo] of its
 * network. A tap opens it when [open] is set.
 */
@Composable
private fun AddressBlock(
    address: String,
    chain: ChainId,
    book: List<AddressBookEntry>,
    accounts: Map<String, InternalAccount>,
    open: ((String) -> Unit)?,
    logo: @Composable () -> Unit,
) {
    ReviewAddress(
        address = address,
        notes = addressNotesOf(address, chain, accounts, book),
        logo = logo,
        onClick = open?.let { tap -> { tap(address) } },
    )
}

@Composable
private fun FactText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
}

/**
 * The origin a record names, as the history shows it. The origin is the wallet's claim, so it shows as text only when every character can
 * be read, the same check the reviews make. Otherwise it shows its UTF-8 bytes in hex, the fallback the reviews give text they cannot
 * show, so a right-to-left override in the claim cannot reorder the line.
 */
internal fun historyOrigin(origin: String): String =
    if (origin.codePoints().allMatch(::isPrintableCodePoint)) origin else origin.toByteArray(Charsets.UTF_8).toHex()

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
    logo: @Composable () -> Unit,
) {
    record.accountIndex?.let { index -> FactRow("Account $index") { FactText(facts.scriptType) } }
    facts.outputs.forEachIndexed { index, output ->
        FactRow("Output $index") {
            val address = output.address
            if (address != null) {
                AddressBlock(address = address, chain = BITCOIN_FAMILY, book = book, accounts = accounts, open = open, logo = logo)
            } else {
                MutedText("No address. The script is not paid to an address.")
            }
            MonoText(btcText(output.amountSats))
            if (output.isOwnChange) MutedText("Your change")
        }
    }
    if (facts.outputsNotKept > 0) FactRow("More") { FactText("${facts.outputsNotKept} outputs not kept") }
    FactRow("Fee") {
        MonoText(facts.feeSats?.let { "$it sat (${btcText(it)})" } ?: "Unknown")
    }
    FactRow("Fee Rate") {
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
    chain: ChainId?,
    book: List<AddressBookEntry>,
    accounts: Map<String, InternalAccount>,
    open: ((String) -> Unit)?,
    logo: @Composable () -> Unit,
) {
    rows.forEachIndexed { index, (label, value) ->
        if (index > 0) Spacer(Modifier.height(LenslySpacing.sm))
        MutedText(label)
        if (isAddressOn(value, chain)) {
            AddressBlock(address = value, chain = requireNotNull(chain), book = book, accounts = accounts, open = open, logo = logo)
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
@Composable
internal fun outcomeTint(outcome: ReviewOutcome): Color = when (outcome) {
    ReviewOutcome.SIGNED -> LocalLumenPalette.current.positive
    ReviewOutcome.REFUSED -> MaterialTheme.colorScheme.error
    ReviewOutcome.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
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

private fun networkLine(record: ReviewRecord): String = record.networkName ?: "The request named no network"

private fun zoned(atMs: Long) = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault())

internal fun dayOf(atMs: Long): LocalDate = zoned(atMs).toLocalDate()

// The formatters are made per call, so they follow the locale the screen is shown in, not the one the app started in.
private fun timeOf(atMs: Long): String = zoned(atMs).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

private fun dateTimeOf(atMs: Long): String =
    zoned(atMs).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
