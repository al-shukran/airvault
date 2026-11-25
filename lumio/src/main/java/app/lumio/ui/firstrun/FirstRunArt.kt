package app.lumio.ui.firstrun

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.R
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon

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

/** The prepare page's art is 220 by 120 dp, and every coordinate below is in dp of the mock's drawing. */
private val PrepareArtSize = Size(220f, 120f)

/** The stroke of the prepare art's outlines. */
private const val PREPARE_STROKE = 1.6f

/**
 * The crossing in reverse: Lensly's pairing code, shown as its three corner squares, travels as light to Lumio, with the spark in the gap. Two phones
 * stand 132 dp apart, Lumio's mark on the left one and the code on the right one, with three dots either side of the spark between them.
 */
@Composable
internal fun PrepareArt() {
    val palette = LocalClearAirPalette.current
    val mark = painterResource(R.drawable.ic_airvault_mark)
    val measurer = rememberTextMeasurer()
    Canvas(modifier = Modifier.size(width = PrepareArtSize.width.dp, height = PrepareArtSize.height.dp)) {
        val outline = Stroke(width = PREPARE_STROKE.dp.toPx())
        listOf(14f, 146f).forEach { left ->
            drawRoundRect(palette.ink, Offset(left.dp.toPx(), 8.dp.toPx()), Size(60.dp.toPx(), 104.dp.toPx()), CornerRadius(12.dp.toPx()), style = outline)
        }
        listOf(37f, 169f).forEach { left ->
            drawLine(palette.ink3, Offset(left.dp.toPx(), 102.dp.toPx()), Offset((left + 14f).dp.toPx(), 102.dp.toPx()), PREPARE_STROKE.dp.toPx(), StrokeCap.Round)
        }
        drawMark(mark, left = 26f, top = 26f, side = 36f)
        label(measurer, "Lumio", centerX = 44f, baseline = 82f, color = palette.ink2)
        listOf(159f to 28f, 181f to 28f, 159f to 50f).forEach { (left, top) ->
            drawRoundRect(palette.ink2, Offset(left.dp.toPx(), top.dp.toPx()), Size(12.dp.toPx(), 12.dp.toPx()), CornerRadius(2.dp.toPx()), style = outline)
        }
        listOf(183f to 52f, 189f to 58f, 176f to 45f).forEach { (left, top) ->
            drawRect(palette.ink2, Offset(left.dp.toPx(), top.dp.toPx()), Size(4.dp.toPx(), 4.dp.toPx()))
        }
        label(measurer, "Lensly", centerX = 176f, baseline = 82f, color = palette.ink2)
        listOf(86f, 96f, 124f, 134f).forEach { x -> drawCircle(palette.ink3, 1.6.dp.toPx(), Offset(x.dp.toPx(), 60.dp.toPx())) }
        drawPath(diamond(cx = 110f, cy = 60f, radius = 7f), palette.spark)
    }
}

/** The AirVault mark from [painter] in a square of [side] dp whose top left is at ([left], [top]) dp. */
private fun DrawScope.drawMark(painter: Painter, left: Float, top: Float, side: Float) {
    translate(left.dp.toPx(), top.dp.toPx()) { with(painter) { draw(Size(side.dp.toPx(), side.dp.toPx())) } }
}

/** A 10 sp semibold label centred on [centerX] dp, standing on [baseline] dp. */
private fun DrawScope.label(measurer: TextMeasurer, text: String, centerX: Float, baseline: Float, color: Color) {
    val layout = measurer.measure(text, ClearAirType.small.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = color))
    drawText(layout, topLeft = Offset(centerX.dp.toPx() - layout.size.width / 2f, baseline.dp.toPx() - layout.firstBaseline))
}

/** A diamond of [radius] dp about ([cx], [cy]) dp. */
private fun DrawScope.diamond(cx: Float, cy: Float, radius: Float): Path = Path().apply {
    moveTo(cx.dp.toPx(), (cy - radius).dp.toPx())
    lineTo((cx + radius).dp.toPx(), cy.dp.toPx())
    lineTo(cx.dp.toPx(), (cy + radius).dp.toPx())
    lineTo((cx - radius).dp.toPx(), cy.dp.toPx())
    close()
}

/** The side of the device protection disc, the stroke of its fingerprint in grid units, and the spark's side and its ring. */
private const val DISC = 120f
private const val FINGERPRINT_SIDE = 66f
private const val FINGERPRINT_STROKE = 1.3f
private const val SPARK_SIDE = 12f
private const val SPARK_RING = 4f

/**
 * One glyph on a raised disc, with the spark at its edge. The spark marks the step that needs you, and a ring in the page colour sets it off from the
 * disc it sits on.
 */
@Composable
internal fun DeviceProtectionArt() {
    val palette = LocalClearAirPalette.current
    Box(modifier = Modifier.size(DISC.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(DISC.dp)) {
            drawCircle(palette.raised)
            val centre = Offset((DISC - 14f - SPARK_SIDE / 2f).dp.toPx(), (12f + SPARK_SIDE / 2f).dp.toPx())
            rotate(45f, pivot = centre) {
                val ring = (SPARK_SIDE + 2 * SPARK_RING).dp.toPx()
                drawRect(palette.background, Offset(centre.x - ring / 2f, centre.y - ring / 2f), Size(ring, ring))
                val side = SPARK_SIDE.dp.toPx()
                drawRect(palette.spark, Offset(centre.x - side / 2f, centre.y - side / 2f), Size(side, side))
            }
        }
        ClearAirIcon(glyph = ClearAirGlyph.FINGERPRINT, color = palette.ink, size = FINGERPRINT_SIDE.dp, stroke = FINGERPRINT_STROKE)
    }
}
