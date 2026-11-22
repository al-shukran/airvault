package app.lumio.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser

/**
 * The Clear Air glyphs. Each is drawn on a 24 unit grid with a 1.6 unit round stroke and no fill, as the proposal's icon set is. The path data is the
 * proposal's own, so a glyph reads the same as its mockup.
 */
enum class ClearAirGlyph(private val data: List<String>) {
    HOME(listOf("M4 10.8l8-6.3 8 6.3v8a1.7 1.7 0 0 1-1.7 1.7H5.7A1.7 1.7 0 0 1 4 18.8z", "M10 20.5v-5.2h4v5.2")),
    ACCOUNTS(listOf("M12 3.6l8.2 4.6-8.2 4.6-8.2-4.6z", "M3.8 12.4l8.2 4.6 8.2-4.6M3.8 16.4l8.2 4.6 8.2-4.6")),
    PORTFOLIO(listOf("M20 12a8 8 0 1 1-8-8v8z", "M15 4.6A8 8 0 0 1 19.4 9H15z")),
    EXPLORE(listOf(circle(12f, 12f, 8.5f), "M15.2 8.8l-1.8 4.6-4.6 1.8 1.8-4.6z")),
    SETTINGS(listOf("M4 7.5h9M18 7.5h2M4 16.5h2M11 16.5h9", circle(15.5f, 7.5f, 2.4f), circle(8.5f, 16.5f, 2.4f))),
    SEARCH(listOf(circle(11f, 11f, 6.5f), "M16 16l4.5 4.5")),
    LOCK(listOf("M7.6 10.5h8.8a2.6 2.6 0 0 1 2.6 2.6v4.8a2.6 2.6 0 0 1-2.6 2.6H7.6a2.6 2.6 0 0 1-2.6-2.6v-4.8a2.6 2.6 0 0 1 2.6-2.6z", "M8.2 10.5V8a3.8 3.8 0 0 1 7.6 0v2.5")),
    BACK(listOf("M19.5 12H5M11 6l-6 6 6 6")),
    DOWN(listOf("M6 9.5l6 6 6-6")),
    CHEVRON(listOf("M9 6l6 6-6 6")),
    COPY(listOf("M9 9h10v10H9z", "M15 9V6.5A1.5 1.5 0 0 0 13.5 5h-8A1.5 1.5 0 0 0 4 6.5v8A1.5 1.5 0 0 0 5.5 16H8")),
    CLOSE(listOf("M6 6l12 12M18 6L6 18")),
    ;

    /** The glyph's paths on the 24 unit grid, parsed once. */
    internal val paths: List<Path> by lazy { data.map { PathParser.createPathFromPathData(it).asComposePath() } }
}

/** A circle of [radius] at ([cx], [cy]), as two half arcs of path data. */
private fun circle(cx: Float, cy: Float, radius: Float): String =
    "M${cx - radius} ${cy}a$radius $radius 0 1 0 ${2 * radius} 0a$radius $radius 0 1 0 ${-2 * radius} 0"

/** The stroke width of a glyph, in its 24 unit grid. The proposal draws every icon at 1.6. */
private const val GLYPH_STROKE = 1.6f

/** The side of a glyph's grid, in units. */
private const val GLYPH_GRID = 24f

/** A glyph in [color], drawn to fill 24 dp. */
@Composable
fun ClearAirIcon(glyph: ClearAirGlyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) { drawGlyph(glyph, color) }
}

private fun DrawScope.drawGlyph(glyph: ClearAirGlyph, color: Color) {
    val unit = size.width / GLYPH_GRID
    withTransform({ scale(unit, unit, pivot = Offset.Zero) }) {
        glyph.paths.forEach { path ->
            drawPath(path = path, color = color, style = Stroke(width = GLYPH_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
