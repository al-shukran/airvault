package app.lumio.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.lensly.app.addressKeyOf
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.tx.TxStatus
import app.lumio.home.Masking
import app.lumio.transactions.ActivityText
import app.lumio.transactions.TxFeedItem
import app.lumio.ui.kit.ADDRESS_LINE_GAP
import app.lumio.ui.kit.ClearAirAddressLine
import app.lumio.ui.kit.CssCentre
import app.lumio.ui.kit.LocalFiat
import app.lumio.ui.kit.inLineBox
import java.time.ZoneId

/** The side of the disc that a direction mark sits in, and the mark's own side. The disc is the mock's sunk 36 dp circle. */
private val DISC = 36.dp
private val MARK = 18.dp

/** The side of a pending row's spark, and how far it sits down from the top of the line it leads. */
private val SPARK = 5.dp
private val SPARK_TOP = 7.dp

/** The value in dollars under an amount: the mono face at 12 sp, regular and a little tight, as the mock sets it. */
private val USD_STYLE = ClearAirType.time.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.02).em).inLineBox()

/** The height of an activity row with a one-line detail. A two-line detail takes the row a little taller. */
private val ROW_MIN_HEIGHT = 64.dp

/** The height of a [frame] row, which is the mock's 62 dp. */
private val FRAME_ROW_MIN_HEIGHT = 62.dp

/**
 * One row of the activity: its direction mark in a sunk disc, what it is and to or from whom, and the amount with its value in dollars on the right.
 * Money in is vault blue and money out is ink, never red. A pending row leads its line with the spark, and says "Confirming" in that line. [names] names
 * the parties by their address key; an other side with no name is its address on the row's last line, after "From" or "To", and [shown] hides the amounts. A [frame] row is set at the approved Asset frame's measures: a 62 dp row, an ink mark on
 * the disc, the detail in the caption face and the value in dollars in the mono face, each in the frame's line box.
 */
@Composable
fun ActivityRowItem(item: TxFeedItem, names: Map<String, String>, zone: ZoneId, shown: Boolean, onClick: () -> Unit, frame: Boolean = false) {
    val palette = LocalClearAirPalette.current
    val tx = item.tx
    val party = tx.counterparty?.let { names[addressKeyOf(it)] }
    val line = remember(item, party, zone) { ActivityText.line(tx, chain = item.chain, usd = item.usd, party = party, zone = zone) }
    val detailStyle = if (frame) ClearAirType.caption.inLineBox() else ClearAirType.body
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (frame) FRAME_ROW_MIN_HEIGHT else ROW_MIN_HEIGHT)
            .lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick)
            .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(DISC).clip(CircleShape).background(palette.raised),
                contentAlignment = Alignment.Center,
            ) {
                TxGlyphIcon(
                    glyph = glyphOf(tx.direction),
                    color = if (line.incoming && !frame) palette.vaultBlue else palette.ink,
                    size = MARK,
                )
            }
            Spacer(Modifier.width(ClearAirSpacing.sm))
            Column(modifier = Modifier.weight(1f).then(if (frame) Modifier.align(CssCentre) else Modifier)) {
                Text(text = line.title, style = if (frame) ClearAirType.rowTitle.inLineBox() else ClearAirType.rowTitle, color = palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.Top) {
                    if (tx.status == TxStatus.PENDING) {
                        SparkMark(side = SPARK, modifier = Modifier.padding(top = SPARK_TOP))
                        Spacer(Modifier.width(ClearAirSpacing.xs))
                    }
                    Text(text = line.detail, style = detailStyle, color = palette.ink2, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.width(ClearAirSpacing.sm))
            Column(modifier = if (frame) Modifier.align(CssCentre) else Modifier, horizontalAlignment = Alignment.End) {
                Text(
                    text = Masking.amount(line.amount, hidden = !shown),
                    style = if (frame) ClearAirType.rowValue.inLineBox() else ClearAirType.rowValue,
                    color = if (line.incoming) palette.vaultBlue else palette.ink,
                    textAlign = TextAlign.End,
                )
                line.usd?.let {
                    Text(text = Masking.amount(LocalFiat.current.amount(it), hidden = !shown), style = if (frame) USD_STYLE else ClearAirType.body, color = palette.ink2, textAlign = TextAlign.End)
                }
            }
        }
        line.address?.let {
            ClearAirAddressLine(
                address = it,
                lead = line.direction,
                leadStyle = detailStyle,
                modifier = Modifier.padding(start = DISC + ClearAirSpacing.sm, top = ADDRESS_LINE_GAP),
            )
        }
    }
}
