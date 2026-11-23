package app.lumio.ui.transactions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.tx.TxDirection

/**
 * The glyphs of a transaction. The kit's set has no arrows, so they are drawn here on the kit's 24 unit grid with its 1.6 unit round stroke. The paths are
 * the mock's own: [RECEIVED] and [SENT] are its arrows, [SETTLED] its tick, [SELF] the transfer to the account itself, and [FAILED] its alert.
 */
internal enum class TxGlyph(private val data: List<String>) {
    RECEIVED(listOf("M17 7L7 17M15 17H7V9")),
    SENT(listOf("M7 17L17 7M9 7h8v8")),
    SETTLED(listOf("M5 12.5l4.5 4.5L19 7.5")),
    SELF(listOf("M7.5 4.5v15M4 16.5l3.5 3.5 3.5-3.5M16.5 19.5v-15M13 7.5l3.5-3.5 3.5 3.5")),
    FAILED(listOf("M12 4l8.5 15H3.5z", "M12 10v4M12 16.8v.1")),
    ;

    /** The glyph's paths on the 24 unit grid, parsed once. */
    internal val paths: List<Path> by lazy { data.map { PathParser.createPathFromPathData(it).asComposePath() } }
}

/** The glyph of a direction: money in is an arrow in, money out an arrow out, and a transfer to the account itself is a swap. */
internal fun glyphOf(direction: TxDirection): TxGlyph = when (direction) {
    TxDirection.IN -> TxGlyph.RECEIVED
    TxDirection.OUT -> TxGlyph.SENT
    TxDirection.SELF -> TxGlyph.SELF
}

/** A transaction glyph in [color], drawn on a square of [size]. */
@Composable
internal fun TxGlyphIcon(glyph: TxGlyph, color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, color) }
}

/** The spark of a pending transaction: a small diamond in the spark colour, which never carries text on its own. */
@Composable
internal fun SparkMark(side: Dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(side).rotate(45f).background(LocalClearAirPalette.current.spark))
}

private fun DrawScope.drawGlyph(glyph: TxGlyph, color: Color) {
    val unit = size.width / GRID
    val style = Stroke(width = STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
    withTransform({ scale(unit, unit, pivot = Offset.Zero) }) {
        glyph.paths.forEach { path -> drawPath(path = path, color = color, style = style) }
    }
}

/** The side of the grid that the glyphs are drawn on. */
private const val GRID = 24f

/** The stroke of a glyph, in its grid units. */
private const val STROKE = 1.6f
