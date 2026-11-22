package app.lumio.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.app.addressKeyOf
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.tx.TxDirection
import app.lumio.home.Masking
import app.lumio.transactions.ActivityText
import app.lumio.transactions.TxFeedItem
import java.time.ZoneId

/** The side of the disc that a direction mark sits in, and the mark's own side. */
private val DISC = 40.dp
private val MARK = 18.dp

/** The height of an activity row with a one-line detail. A two-line detail takes the row a little taller. */
private val ROW_MIN_HEIGHT = 64.dp

/**
 * One row of the activity: its direction mark in a sunk disc, what it is and to or from whom, and the amount with its value in dollars on the right.
 * Money in is vault blue and money out is ink, never red. [names] names the parties by their address key, and [shown] hides the amounts.
 */
@Composable
fun ActivityRowItem(item: TxFeedItem, names: Map<String, String>, zone: ZoneId, shown: Boolean, onClick: () -> Unit) {
    val palette = LocalClearAirPalette.current
    val tx = item.tx
    val party = tx.counterparty?.let { names[addressKeyOf(it)] }
    val line = remember(item, party, zone) { ActivityText.line(tx, chain = item.chain, usd = item.usd, party = party, zone = zone) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ROW_MIN_HEIGHT)
            .lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick)
            .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(DISC).clip(CircleShape).background(palette.raised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when (tx.direction) {
                    TxDirection.IN -> TxIcons.In
                    TxDirection.OUT -> TxIcons.Out
                    TxDirection.SELF -> TxIcons.Self
                },
                contentDescription = null,
                tint = if (line.incoming) palette.vaultBlue else palette.ink2,
                modifier = Modifier.size(MARK),
            )
        }
        Spacer(Modifier.width(ClearAirSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = line.title, style = ClearAirType.rowTitle, color = palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = line.detail, style = ClearAirType.body, color = palette.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(ClearAirSpacing.sm))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = Masking.amount(line.amount, hidden = !shown),
                style = ClearAirType.rowValue,
                color = if (line.incoming) palette.vaultBlue else palette.ink,
                textAlign = TextAlign.End,
            )
            line.fiat?.let {
                Text(text = Masking.amount(it, hidden = !shown), style = ClearAirType.body, color = palette.ink2, textAlign = TextAlign.End)
            }
        }
    }
}
