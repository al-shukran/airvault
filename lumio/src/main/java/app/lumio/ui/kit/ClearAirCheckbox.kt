package app.lumio.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LocalClearAirPalette

/** The box's side, its corner and its edge, as the proposal draws a standard checkbox. */
private val BOX = 22.dp
private val CORNER = 6.dp
private val EDGE = 2.dp

/** The tick's side in dp, centred in the box, and its stroke in the 24 unit grid of the glyph. */
private val TICK = 15.dp
private const val TICK_STROKE = 2.4f
private const val GRID = 24f

/**
 * A standard checkbox, drawn as the proposal draws it: a 22 dp box with a 2 dp ink edge and a 6 dp corner, filled ink with a tick in the page colour
 * when ticked. It holds no state of its own: the row that holds it owns the toggle.
 */
@Composable
fun ClearAirCheckbox(checked: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Canvas(modifier.size(BOX)) {
        if (checked) {
            drawRoundRect(color = palette.ink, cornerRadius = CornerRadius(CORNER.toPx()))
            val tick = TICK.toPx()
            val inset = (size.width - tick) / 2f
            withTransform({
                translate(inset, inset)
                scale(tick / GRID, tick / GRID, pivot = Offset.Zero)
            }) {
                drawPath(
                    path = ClearAirGlyph.CHECK.paths.single(),
                    color = palette.background,
                    style = Stroke(width = TICK_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        } else {
            val edge = EDGE.toPx()
            drawRoundRect(
                color = palette.ink,
                topLeft = Offset(edge / 2f, edge / 2f),
                size = Size(size.width - edge, size.height - edge),
                cornerRadius = CornerRadius(CORNER.toPx() - edge / 2f),
                style = Stroke(width = edge),
            )
        }
    }
}
