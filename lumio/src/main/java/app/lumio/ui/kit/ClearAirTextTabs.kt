package app.lumio.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** The rule under the tabs, a hairline, and the mark under the chosen tab, two units thick. */
private val RULE_WIDTH = 1.dp
private val MARK_WIDTH = 2.dp

/**
 * Text tabs: [options] as words in a row over a hairline. The [selected] word is in ink with a mark under it, and the others in secondary ink. Each
 * word is a tab in TalkBack and keeps a 48 dp touch target. Choosing one calls [onSelect] at once, and the words wrap rather than clip.
 */
@Composable
fun <T> ClearAirTextTabs(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier.fillMaxWidth().drawBehind { rule(color = palette.hairline, width = RULE_WIDTH.toPx()) },
        horizontalArrangement = Arrangement.spacedBy(ClearAirSpacing.lg),
    ) {
        options.forEach { option ->
            val active = option == selected
            Box(
                modifier = Modifier
                    .defaultMinSize(minHeight = ClearAirSpacing.touchTarget)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(option) })
                    .drawBehind { if (active) rule(color = palette.ink, width = MARK_WIDTH.toPx()) }
                    .padding(vertical = ClearAirSpacing.xs),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = label(option),
                    style = ClearAirType.rowTitle,
                    color = if (active) palette.ink else palette.ink2,
                )
            }
        }
    }
}

/** A horizontal rule along the bottom edge of the drawing area, [width] thick. */
private fun DrawScope.rule(color: Color, width: Float) {
    val y = size.height - width / 2f
    drawLine(color = color, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = width)
}
