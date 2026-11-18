package app.lumio.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import app.lensly.app.AddressNotes
import app.lensly.app.InternalAccount
import app.lensly.app.addressKeyOf
import app.lensly.app.headline
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslyMuted
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lumio.data.net.Family
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.home.HomeFormat
import app.lumio.transactions.TxDetail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The test tag of the comment field. */
const val TX_COMMENT_TAG = "tx-comment"

/** The test tag of the explorer button. */
const val TX_EXPLORER_TAG = "tx-explorer"

/**
 * One transaction in full: its direction, amount and value, then its fields, its addresses and its hash, each copyable. The comment is a multi-line field
 * that the owner saves or removes. The explorer button opens the transaction's page when an explorer is on file. The reasons that the spam heuristic gives
 * are listed last, so the owner can see why a row is hidden.
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
) {
    val tx = detail.tx
    val bitcoin = tx.network.family == Family.BTC
    LenslyScreen(title = "TRANSACTION", modifier = modifier, onBack = onBack) {
        Text(text = directionTitle(tx.direction), style = MaterialTheme.typography.titleLarge, color = LenslyOnSurface)
        Text(text = signedAmount(tx), style = MaterialTheme.typography.headlineSmall, color = LenslyOnSurface)
        detail.usd?.let {
            Text(text = HomeFormat.usd(it), style = MaterialTheme.typography.bodyMedium, color = LenslyMuted)
        }
        Spacer(Modifier.height(LenslySpacing.md))

        DetailField("Status", statusText(tx.status))
        DetailField("Network", detail.chain)
        DetailField("Time", detailTime(tx.timestampMs, zone))
        DetailField("Confirmations", tx.confirmations.toString())
        DetailField("Fee", detail.feeText ?: "None")
        tx.memo?.let { DetailField("Memo", it) }
        Spacer(Modifier.height(LenslySpacing.md))

        DetailAddress("Account", tx.account, bitcoin, accounts)
        tx.counterparty?.let { DetailAddress(counterpartyLabel(tx.direction), it, bitcoin, accounts) }
        tx.asset.contract?.let { DetailAddress("Token contract", it, bitcoin = false, accounts) }
        DetailAddress("Transaction hash", tx.hash, bitcoin = false, accounts = emptyMap())
        Spacer(Modifier.height(LenslySpacing.md))

        CommentEditor(saved = detail.comment, onSave = onSaveComment)
        if (detail.explorerUrl != null) {
            Spacer(Modifier.height(LenslySpacing.md))
            PillButton(
                text = "Open in explorer",
                onClick = onExplorer,
                modifier = Modifier.fillMaxWidth().testTag(TX_EXPLORER_TAG),
            )
        }
        if (detail.flags.isNotEmpty()) {
            Spacer(Modifier.height(LenslySpacing.md))
            Text(
                text = "Flagged by the spam check",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            detail.flags.forEach { flag ->
                Text(text = flag, style = MaterialTheme.typography.bodySmall, color = LenslyMuted)
            }
        }
    }
}

/** The comment editor: a multi-line field with the saved text, and Save and Remove. Save is set only when the text has changed. */
@Composable
private fun CommentEditor(saved: String?, onSave: (String) -> Unit) {
    var draft by rememberSaveable(saved) { mutableStateOf(saved.orEmpty()) }
    Text(text = "Comment", style = MaterialTheme.typography.labelLarge, color = LenslyMuted)
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
    Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm)) {
        PillButton(
            text = "Save comment",
            onClick = { onSave(draft.trim()) },
            enabled = draft.trim() != saved.orEmpty().trim(),
            modifier = Modifier.weight(1f),
        )
        if (saved != null) {
            PillButton(text = "Remove", onClick = { onSave("") }, modifier = Modifier.weight(1f))
        }
    }
}

/** One labelled value of the detail, such as the network or the fee. */
@Composable
private fun DetailField(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.xs)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = LenslyMuted)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = LenslyOnSurface)
    }
}

/**
 * An address or a hash with its label. An account of this device in [accounts] is named above the address, as "Your account · <name>". The
 * whole value is shown, and a long press or the copy button copies it.
 */
@Composable
private fun DetailAddress(label: String, value: String, bitcoin: Boolean, accounts: Map<String, InternalAccount>) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.xs)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = LenslyMuted)
        accounts[addressKeyOf(value)]?.let { account -> AddressNotes(internal = account, entry = null).headline() }?.let { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = LenslyOnSurface,
            )
        }
        AddressText(
            address = value,
            shown = value,
            icon = false,
            bitcoin = bitcoin,
            style = MaterialTheme.typography.bodySmall,
            color = LenslyOnSurface,
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
