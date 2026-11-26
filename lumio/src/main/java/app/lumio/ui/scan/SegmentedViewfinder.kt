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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import kotlin.math.roundToInt

/** The camera's corner radius, the number of segments of the bar, and the gap between them, as the mock draws the pairing frame. */
private val CAMERA_RADIUS = 26.dp
private const val SEGMENTS = 20
private val SEGMENT_GAP = 3.dp

/**
 * The camera's backdrop, which shows until the camera's first picture covers it: the mock's radial gradient from [CAMERA_CENTRE] to [CAMERA_EDGE], an
 * ellipse 120 percent of the frame wide and 90 percent tall, centred 40 percent down, that reaches the edge colour at 70 percent. Both themes draw it.
 */
private val CAMERA_CENTRE = Color(0xFF2A2630)
private val CAMERA_EDGE = Color(0xFF0B0A0D)
private const val BACKDROP_WIDTH = 1.2f
private const val BACKDROP_HEIGHT = 0.9f
private const val BACKDROP_TOP = 0.4f
private const val BACKDROP_STOP = 0.7f

/**
 * The corner brackets: a box 196 dp wide, centred in the camera at every width, from 46 dp below its top to 62 dp above its foot, as the mock draws
 * them in its 320 dp camera (62 dp in from each side). Each arm is 34 dp long and 3 dp thick, with a 12 dp outer corner, in paper at 90 percent.
 */
private val BRACKET_BOX_WIDTH = 196.dp
private val BRACKET_TOP = 46.dp
private val BRACKET_BOTTOM = 62.dp
private val BRACKET_ARM = 34.dp
private val BRACKET_STROKE = 3.dp
private val BRACKET_RADIUS = 12.dp
private const val BRACKET_ALPHA = 0.9f

/** The bar: 24 dp in from the sides, 20 dp up from the foot, 4 dp thick. */
private val BAR_SIDE = 24.dp
private val BAR_BOTTOM = 20.dp
private val BAR_HEIGHT = 4.dp
private const val BAR_TRACK_ALPHA = 0.22f

/**
 * The scrim under the caption and the bar, so they read over any picture, even a white page in sunlight: the camera's edge colour, clear 96 dp above the
 * foot and [SCRIM_ALPHA] from 48 dp up (the caption's top is 48 dp up) to the foot. On the dark backdrop it is the colour the backdrop already is.
 */
private val SCRIM_CLEAR = 96.dp
private val SCRIM_FULL = 48.dp
private const val SCRIM_ALPHA = 0.8f

/** The caption: one line across the camera, 30 dp up from its foot, under the brackets and over the bar, in paper at 85 percent. */
private val CAPTION_BOTTOM = 30.dp
private const val CAPTION_ALPHA = 0.85f

/**
 * The viewfinder of the pairing scan, as the mock draws its frame: the camera in [viewport] over a dark backdrop, with rounded corners, four paper
 * corner brackets, the [caption] on one line under them and, under it, a bar of 20 segments that fills with [progress] (all of it when the code is
 * [complete]). The tick per part and the success haptic are the same as the other viewfinders'. The art is drawn over the camera and takes no part in
 * reading the code.
 */
@Composable
fun SegmentedViewfinder(progress: Float?, complete: Boolean, caption: String, modifier: Modifier = Modifier, viewport: @Composable (Modifier) -> Unit) {
    ScanFeedback(progress = progress, complete = complete)
    val filled = if (complete) SEGMENTS else ((progress ?: 0f) * SEGMENTS).roundToInt().coerceIn(0, SEGMENTS)
    val paper = LocalClearAirPalette.current.paper
    Box(modifier = modifier.fillMaxWidth().height(ViewfinderHeight).clip(RoundedCornerShape(CAMERA_RADIUS)).drawBehind { drawCameraBackdrop() }) {
        viewport(Modifier.fillMaxSize())
        Box(modifier = Modifier.fillMaxSize().drawBehind { drawCameraScrim(); drawCameraBrackets(paper) })
        Text(
            text = caption,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = CAPTION_BOTTOM).semantics { liveRegion = LiveRegionMode.Polite },
            style = ClearAirType.time,
            color = paper.copy(alpha = CAPTION_ALPHA),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Bar(filled = filled, modifier = Modifier.align(Alignment.BottomCenter))
        CheckMark(visible = complete, modifier = Modifier.align(Alignment.Center))
    }
}

/** The camera's backdrop, shared by the scan cards: the ellipse is drawn as a circle squashed to the ellipse's height about its centre. */
internal fun DrawScope.drawCameraBackdrop() {
    val centre = Offset(size.width / 2, size.height * BACKDROP_TOP)
    val radius = size.width * BACKDROP_WIDTH
    val squash = size.height * BACKDROP_HEIGHT / radius
    scale(scaleX = 1f, scaleY = squash, pivot = centre) {
        drawRect(
            brush = Brush.radialGradient(0f to CAMERA_CENTRE, BACKDROP_STOP to CAMERA_EDGE, center = centre, radius = radius),
            topLeft = Offset(0f, centre.y - centre.y / squash),
            size = Size(size.width, size.height / squash),
        )
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

/** The scrim under the caption and the bar of a scan card: the camera's edge colour, dense at the foot and clear 96 dp above it. */
internal fun DrawScope.drawCameraScrim() {
    val clear = (size.height - SCRIM_CLEAR.toPx()).coerceAtLeast(0f)
    val full = (size.height - SCRIM_FULL.toPx()).coerceAtLeast(clear)
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            clear / size.height to CAMERA_EDGE.copy(alpha = 0f),
            full / size.height to CAMERA_EDGE.copy(alpha = SCRIM_ALPHA),
            1f to CAMERA_EDGE.copy(alpha = SCRIM_ALPHA),
        ),
    )
}

/** The four corner brackets of a scan card, in [paper] at the mock's alpha: one path, each stroke half its width inside the bracket box. */
internal fun DrawScope.drawCameraBrackets(paper: Color) {
    val stroke = BRACKET_STROKE.toPx()
    val half = stroke / 2f
    val arm = BRACKET_ARM.toPx() - half
    val radius = BRACKET_RADIUS.toPx() - half
    val left = (size.width - BRACKET_BOX_WIDTH.toPx()) / 2f + half
    val right = (size.width + BRACKET_BOX_WIDTH.toPx()) / 2f - half
    val top = BRACKET_TOP.toPx() + half
    val bottom = size.height - BRACKET_BOTTOM.toPx() - half
    val path = Path().apply {
        moveTo(left, top + arm)
        lineTo(left, top + radius)
        arcTo(Rect(left, top, left + 2 * radius, top + 2 * radius), 180f, 90f, false)
        lineTo(left + arm, top)
        moveTo(right - arm, top)
        lineTo(right - radius, top)
        arcTo(Rect(right - 2 * radius, top, right, top + 2 * radius), 270f, 90f, false)
        lineTo(right, top + arm)
        moveTo(right, bottom - arm)
        lineTo(right, bottom - radius)
        arcTo(Rect(right - 2 * radius, bottom - 2 * radius, right, bottom), 0f, 90f, false)
        lineTo(right - arm, bottom)
        moveTo(left + arm, bottom)
        lineTo(left + radius, bottom)
        arcTo(Rect(left, bottom - 2 * radius, left + 2 * radius, bottom), 90f, 90f, false)
        lineTo(left, bottom - arm)
    }
    drawPath(path, paper.copy(alpha = BRACKET_ALPHA), style = Stroke(width = stroke, cap = StrokeCap.Butt))
}
