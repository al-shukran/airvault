package app.lumio.ui.transactions

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lensly.app.AddressBookEntry
import app.lensly.app.AddressNotes
import app.lensly.app.InternalAccount
import app.lensly.app.addressBookEntryFor
import app.lensly.app.addressKeyOf
import app.lensly.app.chainOfAddress
import app.lensly.app.headline
import app.lensly.core.registry.Amounts
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.groupAddress
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LumenMono
import app.lumio.home.HomeFormat
import app.lumio.transactions.TxDetail
import app.lumio.ui.kit.LumioIsland
import app.lumio.ui.kit.LumioPrimaryButton
import app.lumio.ui.kit.LumioTextAction
import app.lumio.ui.kit.rememberCopyAction
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The test tag of the comment field. */
const val TX_COMMENT_TAG = "tx-comment"

/** The test tag of the explorer action. */
const val TX_EXPLORER_TAG = "tx-explorer"

/**
 * One transaction in full (section 2.1, Transaction): a status hero with its direction and amount, then the parties, each address with the name the owner's
 * address book gives it. Every address opens the address-book sheet when [onEditAddress] is set. Then the status, network, time, confirmations, fee and memo,
 * the hash (a tap copies it), the comment and the explorer action. The reasons of the spam heuristic are listed last.
 */
@Composable
fun TransactionDetailScreen(
    detail: TxDetail,
    onBack: () -> Unit,
    onExplorer: () -> Unit,
    onSaveComment: (String) -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    /** The Ethereum accounts of the paired wallets, so an address of one of them is named. */
    accounts: Map<String, InternalAccount> = emptyMap(),
    /** The Address Book of the wallet, which names the addresses it holds. */
    book: List<AddressBookEntry> = emptyList(),
    /** Opens the address-book sheet of an address. Null when the owner's wallet is unknown, so the addresses are not tappable. */
    onEditAddress: ((String) -> Unit)? = null,
) {
    val tx = detail.tx
    LenslyScreen(title = "Transaction", modifier = modifier, onBack = onBack) {
        StatusHero(detail = detail, zone = zone)
        Spacer(Modifier.height(LenslySpacing.lg))
        LumioIsland {
            PartyRow(label = "Account", value = tx.account, accounts = accounts, book = book, onEdit = onEditAddress)
            tx.counterparty?.let { value ->
                PartyRow(label = counterpartyLabel(tx.direction), value = value, accounts = accounts, book = book, onEdit = onEditAddress)
            }
            tx.asset.contract?.let { value ->
                PartyRow(label = "Token contract", value = value, accounts = accounts, book = book, onEdit = onEditAddress)
            }
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        LumioIsland {
            DetailRow("Status", statusText(tx.status))
            DetailRow("Network", detail.chain)
            DetailRow("Time", detailTime(tx.timestampMs, zone))
            DetailRow("Confirmations", tx.confirmations.toString())
            DetailRow("Fee", detail.feeText ?: "None")
            tx.memo?.let { DetailRow("Memo", it) }
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        HashRow(hash = tx.hash)
        Spacer(Modifier.height(LenslySpacing.lg))
        CommentEditor(saved = detail.comment, onSave = onSaveComment)
        detail.explorerUrl?.let {
            Spacer(Modifier.height(LenslySpacing.sm))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                LumioTextAction(text = "Open in explorer", onClick = onExplorer, modifier = Modifier.testTag(TX_EXPLORER_TAG))
            }
        }
        if (detail.flags.isNotEmpty()) {
            Spacer(Modifier.height(LenslySpacing.lg))
            Text(
                text = "Flagged by the spam check",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            detail.flags.forEach { flag ->
                Text(text = flag, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The status hero: the direction in a 56 dp circle, the amount with its direction, its value in dollars, and its time. */
@Composable
private fun StatusHero(detail: TxDetail, zone: ZoneId) {
    val tx = detail.tx
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = directionIcon(tx.direction),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = "${directionTitle(tx.direction)} ${Amounts.format(tx.amount, tx.asset.decimals)} ${tx.asset.symbol}",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        detail.usd?.let {
            Text(text = HomeFormat.usd(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(text = detailTime(tx.timestampMs, zone), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A party of the transaction: its label, the name the owner's address book or accounts give the address above it, and the address in mono. A tap opens
 * the address-book sheet when [onEdit] is set.
 */
@Composable
private fun PartyRow(
    label: String,
    value: String,
    accounts: Map<String, InternalAccount>,
    book: List<AddressBookEntry>,
    onEdit: ((String) -> Unit)?,
) {
    val chain = chainOfAddress(value)
    val name = AddressNotes(
        internal = accounts[addressKeyOf(value)],
        entry = chain?.let { addressBookEntryFor(value, it, book) },
    ).headline()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.sm)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        name?.let {
            Text(text = it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            text = groupAddress(value),
            style = LumenMono,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onEdit != null) Modifier.clickable { onEdit(value) } else Modifier),
        )
    }
}

/** The hash of the transaction in mono. A tap copies it, and the hash then reads "Copied" for 1.5 s. */
@Composable
private fun HashRow(hash: String) {
    val copy = rememberCopyAction(value = hash, label = "Transaction hash")
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.sm)) {
        Text(text = "Transaction hash", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = if (copy.copied) "Copied" else hash,
            style = LumenMono,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().clickable(onClick = copy.copy),
        )
    }
}

/** The comment editor: a multi-line field with the saved text, Save as the primary action and Remove as the text action. */
@Composable
private fun CommentEditor(saved: String?, onSave: (String) -> Unit) {
    var draft by rememberSaveable(saved) { mutableStateOf(saved.orEmpty()) }
    Text(text = "Comment", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(LenslySpacing.xs))
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        placeholder = { Text("Add a note for this transaction") },
        minLines = 3,
        maxLines = 8,
        modifier = Modifier.fillMaxWidth().testTag(TX_COMMENT_TAG),
    )
    Spacer(Modifier.height(LenslySpacing.sm))
    LumioPrimaryButton(
        text = "Save comment",
        onClick = { onSave(draft.trim()) },
        enabled = draft.trim() != saved.orEmpty().trim(),
    )
    if (saved != null) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            LumioTextAction(text = "Remove", onClick = { onSave("") })
        }
    }
}

/** One labelled value of the detail, such as the network or the fee, with the value at the right. */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.md),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The label of the other party: who the money came from, or went to. */
private fun counterpartyLabel(direction: TxDirection): String = when (direction) {
    TxDirection.IN -> "From"
    TxDirection.OUT -> "To"
    TxDirection.SELF -> "Counterparty"
}

/** The status as the detail reads it. */
private fun statusText(status: TxStatus): String = when (status) {
    TxStatus.PENDING -> "Pending, not yet in a block"
    TxStatus.CONFIRMED -> "Confirmed"
    TxStatus.FAILED -> "Failed, so no value moved"
}

/** The full time of [epochMs] in [zone], with seconds, such as "16 Nov 2026, 14:32:05". */
private fun detailTime(epochMs: Long, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss", Locale.getDefault()).withZone(zone).format(Instant.ofEpochMilli(epochMs))
