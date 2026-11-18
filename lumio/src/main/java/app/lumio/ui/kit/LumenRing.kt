package app.lumio.ui.kit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LocalLumenPalette
import app.lensly.ui.theme.LocalReducedMotion

// The Lumen ring's geometry, the same as the mark's (WP7a). It is drawn in a 24-unit box, so at 24 dp one unit is one dp.

/** The side of the box that the ring is drawn in, in units. */
internal const val BOX = 24f

/** The ring's centre and radius, and its stroke, in units of the box. */
private const val CENTRE = 12f
internal const val RADIUS = 8f
internal const val STROKE = 2.5f

/** The gap, in degrees clockwise from 12 o'clock: it runs from 18 to 42, so it is centred on one o'clock. The arc ends are butt caps. */
private const val GAP_FROM = 18f
private const val GAP_TO = 42f

/** The dot in the gap: its centre and radius, in units of the box. */
internal const val DOT_X = 16f
internal const val DOT_Y = 5.0718f
internal const val DOT_RADIUS = 1.25f

/** The arc of a full ring, in degrees: the whole circle less the gap. */
internal const val RING_SWEEP = 360f - (GAP_TO - GAP_FROM)

/** Where the arc starts, in the canvas's degrees: zero is three o'clock, the angle grows clockwise, and 12 o'clock is -90. The arc starts at the far end of the gap. */
internal const val ARC_START = GAP_TO - 90f

/** One turn of a spinning ring, in milliseconds (section 3.4). */
internal const val SPIN_MS = 900

/**
 * The Lumen ring (section 1.6): a ring open at one o'clock, with a solid dot in the gap, as light escapes. It is drawn to [size], and the box scales with
 * it, so at 24 dp the stroke is the spec's 2.5 dp. [progress] from 0 to 1 draws the arc from the gap round to the gap, so 1 is the whole ring and 0 is the
 * dot alone. The ring draws the progress it is given, and its caller moves it. A [spinning] ring turns once every 900 ms, for work with no end. Reduced
 * motion stops the turn.
 */
@Composable
fun LumenRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = LocalLumenPalette.current.ring,
    spinning: Boolean = false,
) {
    if (spinning && !LocalReducedMotion.current) {
        val transition = rememberInfiniteTransition(label = "lumen ring spin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "lumen ring angle",
        )
        Canvas(modifier = modifier.size(size).graphicsLayer { rotationZ = angle }) { drawLumenRing(progress, color) }
    } else {
        Canvas(modifier = modifier.size(size)) { drawLumenRing(progress, color) }
    }
}

/** Draws the ring at [progress] and its dot in the gap, in [color], scaled from the box to the canvas. */
private fun DrawScope.drawLumenRing(progress: Float, color: Color) {
    val unit = size.minDimension / BOX
    val radius = RADIUS * unit
    val sweep = RING_SWEEP * progress.coerceIn(0f, 1f)
    if (sweep > 0f) {
        drawArc(
            color = color,
            startAngle = ARC_START,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(CENTRE * unit - radius, CENTRE * unit - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = STROKE * unit, cap = StrokeCap.Butt),
        )
    }
    drawCircle(color = color, radius = DOT_RADIUS * unit, center = Offset(DOT_X * unit, DOT_Y * unit))
}
