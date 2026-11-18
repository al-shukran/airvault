package app.lumio.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.core.registry.Amounts
import app.lensly.ui.components.ListRow
import app.lensly.ui.theme.LenslySpacing
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.home.HomeFormat
import app.lumio.transactions.TxFeedItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The test tag of a feed row. A test finds the rows by it. */
const val TX_CARD_TAG = "tx-card"

/**
 * One transaction in the feed, in the style of a social feed: the direction mark, who it went to or came from, the chain, the time, and the comment
 * when there is one. The amount and its value in dollars sit at the right. A tap opens the transaction.
 */
@Composable
fun TxCard(item: TxFeedItem, zone: ZoneId, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tx = item.tx
    ListRow(onClick = onClick, modifier = modifier.testTag(TX_CARD_TAG)) {
        DirectionBadge(tx.direction)
        Spacer(Modifier.width(LenslySpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = directionTitle(tx.direction), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = counterpartyLine(tx),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(LenslySpacing.xs))
                ChainBadge(item.chain)
            }
            Text(
                text = timeText(tx.timestampMs, zone) + statusNote(tx.status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            item.comment?.let { comment ->
                Text(
                    text = comment,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontStyle = FontStyle.Italic,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(LenslySpacing.sm))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = signedAmount(tx),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            )
            item.usd?.let {
                Text(text = HomeFormat.usd(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
            }
        }
    }
}

/** The round mark of a direction, which is an arrow in, an arrow out, or a swap for a transfer to the account itself. */
@Composable
private fun DirectionBadge(direction: TxDirection) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = directionIcon(direction),
            contentDescription = directionTitle(direction),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** The chain of a row, in a small rounded label, such as "Ethereum" or "Bitcoin testnet4". */
@Composable
private fun ChainBadge(name: String) {
    Text(
        text = name,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .padding(horizontal = LenslySpacing.xs, vertical = 2.dp),
    )
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
    TxDirection.SELF -> "Self transfer"
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
