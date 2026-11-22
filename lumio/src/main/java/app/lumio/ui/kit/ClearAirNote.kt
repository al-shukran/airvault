package app.lumio.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** The corner radius of an alert note, the proposal's 14 dp. */
private val NOTE_CORNER = 14.dp

/**
 * An inline note: a line of body text under a row or inside a group, with the spark in front of it. It says what did not happen and what happens next,
 * and it never blocks the page around it.
 */
@Composable
fun ClearAirNote(text: String, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter),
        verticalAlignment = Alignment.Top,
    ) {
        Canvas(Modifier.padding(top = 7.dp).size(8.dp)) { drawCircle(color = palette.spark) }
        Spacer(Modifier.width(ClearAirSpacing.xs))
        Text(text = text, style = ClearAirType.body, color = palette.ink2)
    }
}

/**
 * An alert note: one line of fact on the raised layer, with an alert mark, for something the owner must read before acting, such as the networks an
 * address accepts. It is text only and never blocks an action.
 */
@Composable
fun ClearAirAlertNote(text: String, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NOTE_CORNER))
            .background(palette.raised)
            .padding(horizontal = 14.dp, vertical = ClearAirSpacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        ClearAirIcon(glyph = ClearAirGlyph.ALERT, color = palette.ink2)
        Spacer(Modifier.width(ClearAirSpacing.xs))
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = ClearAirType.label.copy(fontWeight = FontWeight.Normal),
            color = palette.ink,
        )
    }
}
