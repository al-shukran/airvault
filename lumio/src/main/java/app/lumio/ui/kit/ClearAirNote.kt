package app.lumio.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

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
