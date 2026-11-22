package app.lumio.ui.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** The height of a row with one line of text, from the proposal. A row whose text wraps grows with it. */
private val ROW_HEIGHT = 62.dp

/**
 * A list row whose text wraps rather than clips: an optional [leading] mark, a [title] with a [description] under it, an optional [trailing] value, and
 * a chevron when the row opens something ([onClick]). A long name runs to a second line, and no label ends in an ellipsis. The row scales to 0.98 while
 * pressed.
 */
@Composable
fun ClearAirListRow(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    description: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ROW_HEIGHT)
            .then(
                if (onClick != null) {
                    Modifier.lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(ClearAirSpacing.sm))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = ClearAirType.rowTitle, color = palette.ink)
            if (description != null) {
                Text(text = description, style = ClearAirType.body, color = palette.ink2)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(ClearAirSpacing.sm))
            trailing()
        }
        if (onClick != null) {
            Spacer(Modifier.width(ClearAirSpacing.xxs))
            ClearAirIcon(glyph = ClearAirGlyph.CHEVRON, color = palette.ink3)
        }
    }
}
