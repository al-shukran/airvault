package app.lumio.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import kotlin.math.roundToInt

/** The camera's corner radius, the number of segments of the bar, and the gap between them, as the mock draws the pairing frame. */
private val CAMERA_RADIUS = 26.dp
private const val SEGMENTS = 20
private val SEGMENT_GAP = 3.dp

/** One corner bracket: its box, the stroke, the radius of its outer corner, and where it sits in the camera (62 dp in from the sides and the foot, 46 dp from the top). */
private val BRACKET_BOX = 34.dp
private val BRACKET_STROKE = 3.dp
private val BRACKET_RADIUS = 12.dp
private val BRACKET_SIDE = 62.dp
private val BRACKET_TOP = 46.dp
private val BRACKET_BOTTOM = 62.dp
private const val BRACKET_ALPHA = 0.9f

/** The bar: 24 dp in from the sides, 20 dp up from the foot, 4 dp thick. */
private val BAR_SIDE = 24.dp
private val BAR_BOTTOM = 20.dp
private val BAR_HEIGHT = 4.dp
private const val BAR_TRACK_ALPHA = 0.22f

/** The caption, 30 dp up from the foot of the camera, in paper at 85 percent, over a shade that fades up from the foot so it reads on any picture. */
private val CAPTION_BOTTOM = 30.dp
private val CAPTION_SIDE = 24.dp
private val CAPTION_SHADE_TOP = 40.dp
private const val CAPTION_ALPHA = 0.85f
private const val CAPTION_SHADE = 0.55f

/**
 * The viewfinder of the pairing scan, as the mock draws its frame: the camera in [viewport] with rounded corners, four paper corner brackets, the
 * [caption] near its foot and, under it, a bar of 20 segments that fills with [progress] (all of it when the code is [complete]). The tick per part and
 * the success haptic are the same as the other viewfinders'. The art is drawn over the camera and takes no part in reading the code.
 */
@Composable
fun SegmentedViewfinder(progress: Float?, complete: Boolean, caption: String, modifier: Modifier = Modifier, viewport: @Composable (Modifier) -> Unit) {
    ScanFeedback(progress = progress, complete = complete)
    val filled = if (complete) SEGMENTS else ((progress ?: 0f) * SEGMENTS).roundToInt().coerceIn(0, SEGMENTS)
    Box(modifier = modifier.fillMaxWidth().height(ViewfinderHeight)) {
        viewport(Modifier.fillMaxSize().clip(RoundedCornerShape(CAMERA_RADIUS)))
        Caption(text = caption, modifier = Modifier.align(Alignment.BottomCenter))
        Brackets(modifier = Modifier.fillMaxSize())
        Bar(filled = filled, modifier = Modifier.align(Alignment.BottomCenter))
        CheckMark(visible = complete, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier) {
    val palette = LocalClearAirPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = CAMERA_RADIUS, bottomEnd = CAMERA_RADIUS))
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = CAPTION_SHADE))))
            .padding(start = CAPTION_SIDE, end = CAPTION_SIDE, top = CAPTION_SHADE_TOP, bottom = CAPTION_BOTTOM),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = ClearAirType.time, color = palette.paper.copy(alpha = CAPTION_ALPHA), textAlign = TextAlign.Center)
    }
}

@Composable
private fun Bar(filled: Int, modifier: Modifier) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier.fillMaxWidth().padding(start = BAR_SIDE, end = BAR_SIDE, bottom = BAR_BOTTOM),
        horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
    ) {
        repeat(SEGMENTS) { index ->
            val color = if (index < filled) palette.spark else palette.paper.copy(alpha = BAR_TRACK_ALPHA)
            Box(modifier = Modifier.weight(1f).height(BAR_HEIGHT).clip(RoundedCornerShape(BAR_HEIGHT / 2)).background(color))
        }
    }
}

/** The four brackets: a box of two borders and one rounded corner, the top left one mirrored into the other three. */
@Composable
private fun Brackets(modifier: Modifier) {
    val paper = LocalClearAirPalette.current.paper.copy(alpha = BRACKET_ALPHA)
    Box(
        modifier = modifier.drawBehind {
            val box = BRACKET_BOX.toPx()
            val stroke = BRACKET_STROKE.toPx()
            val radius = BRACKET_RADIUS.toPx() - stroke / 2
            val half = stroke / 2
            // The stroke runs down the middle of the border, so the path sits half a stroke in from the box's edge.
            val corner = Path().apply {
                moveTo(half, box)
                lineTo(half, half + radius)
                arcTo(Rect(half, half, half + 2 * radius, half + 2 * radius), 180f, 90f, false)
                lineTo(box, half)
            }
            val left = BRACKET_SIDE.toPx()
            val top = BRACKET_TOP.toPx()
            val bottom = size.height - BRACKET_BOTTOM.toPx() - box
            val right = size.width - BRACKET_SIDE.toPx() - box
            for ((x, y, flipX, flipY) in listOf(
                Bracket(left, top, false, false),
                Bracket(right, top, true, false),
                Bracket(left, bottom, false, true),
                Bracket(right, bottom, true, true),
            )) {
                withTransform({
                    translate(x, y)
                    scale(if (flipX) -1f else 1f, if (flipY) -1f else 1f, pivot = Offset(box / 2, box / 2))
                }) { drawPath(corner, paper, style = Stroke(width = stroke)) }
            }
        },
    )
}

private data class Bracket(val x: Float, val y: Float, val flipX: Boolean, val flipY: Boolean)
