package app.lumio.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lensly.core.registry.Amounts
import app.lensly.ui.components.LumioRow
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.home.HomeFormat
import app.lumio.home.Masking
import app.lumio.transactions.TxFeedItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The test tag of an activity row. A test finds the rows by it. */
const val TX_ROW_TAG = "tx-row"

/**
 * One transaction of an activity island (section 2.1): its direction, the counterparty and the time under it, and the signed amount with its value in
 * dollars at the right. [shown] is false while the owner hides balances, and the amounts then read as the mask. A tap opens the transaction.
 */
@Composable
fun ActivityRow(item: TxFeedItem, zone: ZoneId, onClick: () -> Unit, modifier: Modifier = Modifier, shown: Boolean = true) {
    val tx = item.tx
    LumioRow(
        title = directionTitle(tx.direction),
        description = counterpartyLine(tx) + " · " + timeText(tx.timestampMs, zone) + statusNote(tx.status),
        modifier = modifier.testTag(TX_ROW_TAG),
        leading = { DirectionMark(tx.direction) },
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Masking.amount(signedAmount(tx), hidden = !shown),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                )
                item.usd?.let {
                    Text(
                        text = Masking.amount(HomeFormat.usd(it), hidden = !shown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
        },
        onClick = onClick,
    )
}

/** The round mark of a direction, which is an arrow in, an arrow out, or a swap for a transfer to the account itself. */
@Composable
private fun DirectionMark(direction: TxDirection) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = directionIcon(direction),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** The mark of [direction]. */
internal fun directionIcon(direction: TxDirection): ImageVector = when (direction) {
    TxDirection.IN -> TxIcons.In
    TxDirection.OUT -> TxIcons.Out
    TxDirection.SELF -> TxIcons.Self
}

/** The name of [direction] as the owner reads it. */
fun directionTitle(direction: TxDirection): String = when (direction) {
    TxDirection.IN -> "Received"
    TxDirection.OUT -> "Sent"
    TxDirection.SELF -> "Self Transfer"
}

/** The other party of [tx], such as "From 0xab12...cd34", or "To yourself" for a transfer to the account itself. */
fun counterpartyLine(tx: LumioTx): String = when (tx.direction) {
    TxDirection.IN -> tx.counterparty?.let { "From ${shortHex(it)}" } ?: "Received"
    TxDirection.OUT -> tx.counterparty?.let { "To ${shortHex(it)}" } ?: "Sent"
    TxDirection.SELF -> "To yourself"
}

/** The amount of [tx] with its sign and its symbol, such as "+1.5 ETH", "-0.25 BTC" or "10 USDC" for a transfer to the account itself. */
fun signedAmount(tx: LumioTx): String {
    val amount = Amounts.format(tx.amount, tx.asset.decimals)
    val sign = when (tx.direction) {
        TxDirection.IN -> "+"
        TxDirection.OUT -> "-"
        TxDirection.SELF -> ""
    }
    return "$sign$amount ${tx.asset.symbol}"
}

/** A hash or an address shortened to its first six and last four characters. A short value is shown whole. */
fun shortHex(value: String): String = if (value.length <= SHORT_MIN) value else value.take(SHORT_HEAD) + "..." + value.takeLast(SHORT_TAIL)

/** The time of [epochMs] in [zone], such as "16 Nov 2026, 14:32". */
fun timeText(epochMs: Long, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault()).withZone(zone).format(Instant.ofEpochMilli(epochMs))

/** The note that a status adds to a row's time: " · Pending" or " · Failed", and nothing for a confirmed transaction. */
fun statusNote(status: TxStatus): String = when (status) {
    TxStatus.PENDING -> " · Pending"
    TxStatus.FAILED -> " · Failed"
    TxStatus.CONFIRMED -> ""
}

private const val SHORT_MIN = 12
private const val SHORT_HEAD = 6
private const val SHORT_TAIL = 4
