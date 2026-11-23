package app.lumio.ui.network

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser

/** The scan glyph of Scan to Pay, as the mock draws it, on the 24 unit grid. */
internal const val SCAN_GLYPH = "M4 9V6.5A2.5 2.5 0 0 1 6.5 4H9M15 4h2.5A2.5 2.5 0 0 1 20 6.5V9M20 15v2.5a2.5 2.5 0 0 1-2.5 2.5H15M9 20H6.5A2.5 2.5 0 0 1 4 17.5V15M7.5 12h9"

/** The plus of Pair Another Wallet and Add Custom Test Network, as the mock draws it. */
internal const val PLUS_GLYPH = "M12 5v14M5 12h14"

/** The side of a glyph's grid, in units, and the 1.6 unit round stroke that every Clear Air icon is drawn with. */
private const val GRID = 24f
private const val GLYPH_STROKE = 1.6f

/**
 * A glyph that the shared Clear Air set does not carry: the SVG path [data] drawn on the 24 unit grid, with the same stroke as the shared icons.
 */
@Composable
internal fun PanelGlyph(data: String, color: Color) {
    val path = remember(data) { PathParser.createPathFromPathData(data).asComposePath() }
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / GRID
        withTransform({ scale(unit, unit, pivot = Offset.Zero) }) {
            drawPath(path = path, color = color, style = Stroke(width = GLYPH_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
