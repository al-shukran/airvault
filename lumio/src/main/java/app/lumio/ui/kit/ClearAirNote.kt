package app.lumio.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

/** The corner radius of a note, the proposal's 14 dp. */
private val NOTE_CORNER = 14.dp

/**
 * An inline note: a line of fact on the raised layer with an info mark, under a row or inside a group. It says what did not happen and what happens
 * next, and it never blocks the page around it. It sits inside the page gutter.
 */
@Composable
fun ClearAirNote(text: String, modifier: Modifier = Modifier) {
    NoteCard(glyph = ClearAirGlyph.INFO, text = text, modifier = modifier.padding(horizontal = ClearAirSpacing.gutter))
}

/**
 * An alert note: the same card with an alert mark, for something the owner must read before acting, such as the networks an address accepts. It is
 * text only and never blocks an action. The caller sets its page gutter.
 */
@Composable
fun ClearAirAlertNote(text: String, modifier: Modifier = Modifier) {
    NoteCard(glyph = ClearAirGlyph.ALERT, text = text, modifier = modifier)
}

@Composable
private fun NoteCard(glyph: ClearAirGlyph, text: String, modifier: Modifier) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NOTE_CORNER))
            .background(palette.raised)
            .padding(horizontal = 14.dp, vertical = ClearAirSpacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        ClearAirIcon(glyph = glyph, color = palette.ink2, size = 18.dp, modifier = Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = ClearAirType.label.copy(fontWeight = FontWeight.Normal),
            color = palette.ink,
        )
    }
}
