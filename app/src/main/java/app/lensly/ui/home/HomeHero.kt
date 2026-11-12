package app.lensly.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lensly.ui.settings.animatorsDisabled
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySurfaceContainer
import app.lensly.ui.theme.LenslySurfaceHigh
import kotlin.math.cos
import kotlin.math.sin

/** The test tag of the aperture, so a test can read its pixels. */
internal const val HOME_HERO_TAG = "home-hero"

/** Six blades, which repeat every 60 degrees: a turn of 60 degrees draws the same picture as the start. */
private const val BLADES = 6
private const val BLADE_STEP_DEG = 60f

/** The trailing edge of a blade leans this far round the rim, and its leading edge runs [BLADE_OVERLAP_DEG] past the next blade. */
private const val BLADE_TILT_DEG = 32f
private const val BLADE_OVERLAP_DEG = 26f

/** The hole at the centre and the reach of the blades, as fractions of the aperture radius. */
private const val HOLE_FRACTION = 0.34f
private const val REACH_FRACTION = 0.98f

/**
 * The aperture's radius: a share of the hero's width, or of its height, whichever is less. The height share is the larger one, so a
 * short hero keeps the aperture a little bigger than a square one would.
 */
private const val APERTURE_WIDTH_FRACTION = 0.40f
private const val APERTURE_HEIGHT_FRACTION = 0.44f
private const val GLOW_FRACTION = 1.6f
private const val BARREL_FRACTION = 1.04f

/** The arc of the specular highlight, on the upper left of the rim, in degrees. */
private const val HIGHLIGHT_START_DEG = 200f
private const val HIGHLIGHT_SWEEP_DEG = 56f

/** One full turn of the blades. The rim and the glow never move. */
private const val SPIN_CYCLE_MS = 24_000

/**
 * The Home artwork: a camera aperture of six overlapping blades, filled from the accent red to a dark grey and lit along their
 * edges, set in a glass rim with a highlight, over a soft accent glow. The blades turn slowly. With animations off in the system
 * settings (Settings.Global.ANIMATOR_DURATION_SCALE is 0) the aperture is drawn once and never changes.
 *
 * The paths, brushes and strokes are built once per size, in the cache. A frame only reads the turn and draws them, so the draw
 * lambda allocates nothing.
 */
@Composable
internal fun ApertureHero(modifier: Modifier = Modifier) {
    val spin = rememberSpinDegrees()
    Box(
        modifier = modifier
            .testTag(HOME_HERO_TAG)
            .semantics { contentDescription = "Lensly aperture" }
            .drawWithCache {
                val centre = Offset(size.width / 2f, size.height / 2f)
                val radius = minOf(size.width * APERTURE_WIDTH_FRACTION, size.height * APERTURE_HEIGHT_FRACTION)
                val hole = radius * HOLE_FRACTION
                val blade = bladePath(centre, radius)
                val bladeFill = Brush.radialGradient(
                    colors = listOf(LenslyAccent, LenslyAccent.copy(alpha = 0.8f), LenslySurfaceHigh.copy(alpha = 0.7f)),
                    center = centre,
                    radius = radius,
                )
                val bladeEdge = Stroke(width = 1.dp.toPx(), join = StrokeJoin.Round)
                val bladeEdgeColour = LenslyOnSurface.copy(alpha = 0.55f)
                val glow = Brush.radialGradient(
                    colors = listOf(LenslyAccent.copy(alpha = 0.40f), LenslyAccent.copy(alpha = 0f)),
                    center = centre,
                    radius = radius * GLOW_FRACTION,
                )
                val holeGlow = Brush.radialGradient(
                    colors = listOf(LenslyAccent.copy(alpha = 0.55f), LenslyAccent.copy(alpha = 0f)),
                    center = centre,
                    radius = hole * 1.4f,
                )
                val barrel = Brush.radialGradient(
                    colors = listOf(LenslySurfaceHigh, LenslySurfaceContainer, LenslyBlack),
                    center = centre,
                    radius = radius * BARREL_FRACTION,
                )
                val rimColour = Color.White.copy(alpha = 0.22f)
                val rimStroke = Stroke(width = 2.dp.toPx())
                val haloColour = Color.White.copy(alpha = 0.10f)
                val haloStroke = Stroke(width = 1.dp.toPx())
                val highlightColour = Color.White.copy(alpha = 0.9f)
                val highlightStroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                val highlightTopLeft = Offset(centre.x - radius, centre.y - radius)
                val highlightSize = Size(radius * 2f, radius * 2f)
                onDrawBehind {
                    drawCircle(brush = glow, radius = radius * GLOW_FRACTION, center = centre)
                    drawCircle(brush = barrel, radius = radius * BARREL_FRACTION, center = centre)
                    drawCircle(color = haloColour, radius = radius * BARREL_FRACTION, center = centre, style = haloStroke)
                    drawCircle(brush = holeGlow, radius = hole, center = centre)
                    val turn = spin.value
                    for (index in 0 until BLADES) {
                        withTransform({ rotate(degrees = turn + index * BLADE_STEP_DEG, pivot = centre) }) {
                            drawPath(path = blade, brush = bladeFill)
                            drawPath(path = blade, color = bladeEdgeColour, style = bladeEdge)
                        }
                    }
                    drawCircle(color = rimColour, radius = radius, center = centre, style = rimStroke)
                    drawArc(
                        color = highlightColour,
                        startAngle = HIGHLIGHT_START_DEG,
                        sweepAngle = HIGHLIGHT_SWEEP_DEG,
                        useCenter = false,
                        topLeft = highlightTopLeft,
                        size = highlightSize,
                        style = highlightStroke,
                    )
                }
            },
    )
}

/**
 * The turn of the blades, in degrees. With animations off it is a fixed 0, and no transition runs, so nothing redraws on its own. It is
 * internal so a test can follow the turn over a paused clock.
 */
@Composable
internal fun rememberSpinDegrees(): State<Float> {
    val context = LocalContext.current
    val still = remember(context) { animatorsDisabled(context) }
    if (still) return StillTurn
    val transition = rememberInfiniteTransition(label = "aperture")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = SPIN_CYCLE_MS, easing = LinearEasing)),
        label = "aperture-spin",
    )
}

/** The turn of an aperture that does not move: 0 degrees, always. It is one shared state, so a still aperture allocates nothing. */
private val StillTurn: State<Float> = object : State<Float> {
    override val value: Float get() = 0f
}

/**
 * One blade, at the top of the aperture, as a closed path: its inner edge runs along the hole's side from 0 to 60 degrees, and its
 * outer edge follows the rim from the leading point back to the trailing point. The six blades are this path, turned in 60-degree
 * steps.
 */
private fun bladePath(centre: Offset, radius: Float): Path {
    val hole = radius * HOLE_FRACTION
    val reach = radius * REACH_FRACTION
    val reachBounds = Rect(centre.x - reach, centre.y - reach, centre.x + reach, centre.y + reach)
    val leading = BLADE_STEP_DEG + BLADE_TILT_DEG + BLADE_OVERLAP_DEG
    val start = pointAt(centre, hole, 0f)
    val holeEnd = pointAt(centre, hole, BLADE_STEP_DEG)
    val leadingPoint = pointAt(centre, reach, leading)
    return Path().apply {
        moveTo(start.x, start.y)
        lineTo(holeEnd.x, holeEnd.y)
        lineTo(leadingPoint.x, leadingPoint.y)
        arcTo(
            rect = reachBounds,
            startAngleDegrees = leading,
            sweepAngleDegrees = BLADE_TILT_DEG - leading,
            forceMoveTo = false,
        )
        close()
    }
}

/** The point [distance] from [centre] at [degrees], measured clockwise from the right, as on screen. */
private fun pointAt(centre: Offset, distance: Float, degrees: Float): Offset {
    val radians = Math.toRadians(degrees.toDouble())
    return Offset(centre.x + distance * cos(radians).toFloat(), centre.y + distance * sin(radians).toFloat())
}
