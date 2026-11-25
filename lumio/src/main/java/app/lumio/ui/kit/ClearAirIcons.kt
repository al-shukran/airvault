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
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
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
    CHECK(listOf("M5 12.5l4.5 4.5L19 7.5")),
    INFO(listOf(circle(12f, 12f, 8.5f), "M12 11v5.5M12 7.8v.1")),
    ALERT(listOf("M12 4l8.5 15H3.5z", "M12 10v4M12 16.8v.1")),
    STAR(listOf("M12 4.3L14 10.05L20.08 10.17L15.23 13.85L17 19.68L12 16.2L7 19.68L8.77 13.85L3.92 10.17L10 10.05Z")),
    SCAN(listOf("M4 9V6.5A2.5 2.5 0 0 1 6.5 4H9M15 4h2.5A2.5 2.5 0 0 1 20 6.5V9M20 15v2.5a2.5 2.5 0 0 1-2.5 2.5H15M9 20H6.5A2.5 2.5 0 0 1 4 17.5V15M7.5 12h9")),
    PLUS(listOf("M12 5v14M5 12h14")),
    DRAG(listOf("M5 9h14M5 15h14")),
    SEND(listOf("M7 17L17 7M9 7h8v8")),
    RECEIVE(listOf("M17 7L7 17M15 17H7V9")),
    SHARE(listOf("M12 3.5v11M7.5 8L12 3.5 16.5 8M5 13v4.6A2.4 2.4 0 0 0 7.4 20h9.2a2.4 2.4 0 0 0 2.4-2.4V13")),
    PASTE(listOf("M7.9 5h8.2a2.4 2.4 0 0 1 2.4 2.4v10.7a2.4 2.4 0 0 1-2.4 2.4H7.9a2.4 2.4 0 0 1-2.4-2.4V7.4A2.4 2.4 0 0 1 7.9 5z", "M9 5V3.8h6V5M9 11h6M9 14.5h4")),
    EYE(listOf("M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z", circle(12f, 12f, 3f))),
    EYE_OFF(
        listOf(
            "M4.5 9.6a11 11 0 0 1 4.2-2.5M14 6.6a11 11 0 0 1 5.5 3M7.6 12.9a6.5 6.5 0 0 1 2.7-1.7M16.4 12.9a6.5 6.5 0 0 0-1.4-1M10.4 16.2a2.4 2.4 0 0 1 3.2 0M4 4l16 16",
        ),
    ),
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

/**
 * A glyph in [color], drawn to fill [size], which is 24 dp unless a mock draws it smaller. A [filled] glyph is solid, as the watched star is; the others
 * are strokes.
 */
@Composable
fun ClearAirIcon(glyph: ClearAirGlyph, color: Color, modifier: Modifier = Modifier, filled: Boolean = false, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, color, filled) }
}

private fun DrawScope.drawGlyph(glyph: ClearAirGlyph, color: Color, filled: Boolean) {
    val unit = size.width / GLYPH_GRID
    val style = if (filled) Fill else Stroke(width = GLYPH_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
    withTransform({ scale(unit, unit, pivot = Offset.Zero) }) {
        glyph.paths.forEach { path -> drawPath(path = path, color = color, style = style) }
    }
}
