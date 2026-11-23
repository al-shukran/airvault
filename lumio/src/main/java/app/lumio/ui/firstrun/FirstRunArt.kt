package app.lumio.ui.firstrun

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LocalClearAirPalette

/*
 * The art of the first-run statements is drawn from the AirVault mark's slabs: an isometric block with a top face and two sides, in the mark's own
 * blues, with the amber spark where attention goes. A solid slab holds keys and a dashed one is empty. Each piece is drawn in code on one grid, so
 * there is no raster image and no stock 3D art.
 */

/** The art grid, in dp. Every composition below is placed on it. */
private const val GRID_WIDTH = 150f
private const val GRID_HEIGHT = 96f

/** The mark's faces: the top, the left side and the right side. */
private val TopFace = Color(0xFF8DBBFF)
private val LeftFace = Color(0xFF3F7BE0)
private val RightFace = Color(0xFF2A56A8)

/** Keys stay on the phone: one solid slab, with the spark on its top face. */
@Composable
internal fun KeysArt() {
    val spark = LocalClearAirPalette.current.spark
    ArtCanvas { solidSlab(cx = 75f, top = 19f, spark = spark) }
}

/** Keys are never shared: the one full slab stands between two empty dashed ones. */
@Composable
internal fun NeverShareArt() {
    val palette = LocalClearAirPalette.current
    ArtCanvas {
        emptySlab(cx = 36f, top = 32f, outline = palette.ink3)
        emptySlab(cx = 114f, top = 32f, outline = palette.ink3)
        solidSlab(cx = 75f, top = 19f, spark = palette.spark)
    }
}

/** Provided as is: two plain solid slabs, with no spark. */
@Composable
internal fun ProvidedAsIsArt() {
    ArtCanvas {
        solidSlab(cx = 56f, top = 26f, spark = null)
        solidSlab(cx = 100f, top = 8f, spark = null)
    }
}

/** Duress: the real vault slab next to an empty dashed decoy, as the design draws it. */
@Composable
internal fun DuressArt() {
    val palette = LocalClearAirPalette.current
    ArtCanvas {
        solidSlab(cx = 40f, top = 10f, spark = palette.spark)
        emptySlab(cx = 108f, top = 22f, outline = palette.ink3)
    }
}

@Composable
private fun ArtCanvas(draw: DrawScope.() -> Unit) {
    Canvas(modifier = Modifier.size(width = GRID_WIDTH.dp, height = GRID_HEIGHT.dp), onDraw = draw)
}

/** How many pixels one grid unit is. The grid keeps its proportions at any size the canvas is given. */
private val DrawScope.unit: Float get() = size.width / GRID_WIDTH

/** A closed polygon through [points], given in grid units. */
private fun DrawScope.polygon(vararg points: Pair<Float, Float>): Path = Path().apply {
    points.forEachIndexed { index, (x, y) ->
        if (index == 0) moveTo(x * unit, y * unit) else lineTo(x * unit, y * unit)
    }
    close()
}

/**
 * A solid slab whose top vertex is at ([cx], [top]): its three faces in the mark's blues, and the spark on the top face when [spark] is given.
 * Its proportions are those of the design's own slab, 56 grid units wide.
 */
private fun DrawScope.solidSlab(cx: Float, top: Float, spark: Color?) {
    drawPath(polygon(cx to top, cx + 28f to top + 16f, cx to top + 32f, cx - 28f to top + 16f), TopFace)
    drawPath(polygon(cx - 28f to top + 16f, cx to top + 32f, cx to top + 58f, cx - 28f to top + 42f), LeftFace)
    drawPath(polygon(cx + 28f to top + 16f, cx to top + 32f, cx to top + 58f, cx + 28f to top + 42f), RightFace)
    if (spark != null) {
        drawPath(polygon(cx to top + 22f, cx + 3f to top + 26.5f, cx to top + 31f, cx - 3f to top + 26.5f), spark)
    }
}

/** The same slab as a dashed outline with nothing in it: the place where a vault slab would be. */
private fun DrawScope.emptySlab(cx: Float, top: Float, outline: Color) {
    val style = Stroke(width = 1.6f * unit, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * unit, 3f * unit)))
    drawPath(polygon(cx to top, cx + 28f to top + 16f, cx to top + 32f, cx - 28f to top + 16f), outline, style = style)
    drawPath(polygon(cx - 28f to top + 16f, cx to top + 32f, cx to top + 58f, cx - 28f to top + 42f), outline, style = style)
    drawPath(polygon(cx + 28f to top + 16f, cx to top + 32f, cx to top + 58f, cx + 28f to top + 42f), outline, style = style)
}
