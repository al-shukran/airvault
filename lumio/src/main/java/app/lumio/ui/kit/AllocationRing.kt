package app.lumio.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LocalReducedMotion
import app.lensly.ui.theme.LumenMotion
import app.lumio.home.HomeFormat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** How much of its colour a dimmed segment keeps. The rest is blended into the page, so the dim is opaque and no seam shows where pieces overlap. */
private const val DIM_KEEP = 0.4f

/** How much a selected segment grows, as a factor of its size: it lifts outward and thickens by a tenth. */
private const val LIFT = 0.1f

/** How far from the band of the ring a tap still counts as a tap on it. */
private val TAP_SLOP = 8.dp

/** What the allocation ring shows (sections 1.6 and 2.1). [Ready] draws one segment per asset, sized by its value. Every other state draws the plain Lumen ring. */
sealed interface AllocationState {
    /** The balances are still being read: the plain ring turns, or reads "Reading…" under reduced motion. */
    data object Loading : AllocationState

    /** No holding has a price: the plain ring, and a total of $0.00. */
    data object Empty : AllocationState

    /** The owner hides totals or balances: the plain ring and the mask. No segment is drawn, so the shape of the allocation stays private. */
    data object Hidden : AllocationState

    /** The priced holdings, one slice per asset. */
    data class Ready(val slices: List<AllocationSlice>) : AllocationState
}

/**
 * The allocation of a wallet, or of all wallets, drawn as a Lumen ring (section 1.6). Each asset is a segment of the ring, sized by its share of the total,
 * with a small gap between segments and rounded ends. The dot in the gap at one o'clock stays, so the ring is still the mark. The total sits in the centre,
 * and a hidden total shows the mask.
 *
 * Shares spring from where they are when the balances change, and a new segment grows from nothing, so the plain ring turns into the allocation. Reduced
 * motion snaps every change. [selectedAssetId] lifts its segment and dims the others. A tap on a segment calls [onSelect] with its id, and a tap on the same
 * segment, on the gap, or in the centre calls it with null. Without [onSelect] the ring takes no taps. [showCentre] is false for a small ring that has no
 * room for the total.
 */
@Composable
fun AllocationRing(
    state: AllocationState,
    modifier: Modifier = Modifier,
    diameter: Dp = 232.dp,
    selectedAssetId: String? = null,
    onSelect: ((String?) -> Unit)? = null,
    showCentre: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val background = scheme.background
    val mark = if (AllocationPalette.isDark(background)) scheme.primary else scheme.onSurface
    val reduced = LocalReducedMotion.current
    val hidden = state == AllocationState.Hidden
    val instant = reduced || hidden
    val shares = remember(state, background) {
        AllocationMath.shares((state as? AllocationState.Ready)?.slices.orEmpty(), AllocationPalette.otherOf(background))
    }
    val motion = remember { AllocationMotion(shares, selectedAssetId) }
    LaunchedEffect(shares, selectedAssetId, instant) { motion.sync(shares, selectedAssetId, instant) }

    Box(
        modifier = modifier
            .size(diameter)
            .semantics(mergeDescendants = true) { contentDescription = describe(state) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .spin(enabled = state == AllocationState.Loading && !reduced)
                .drawRing(motion, background, mark)
                .tapSegments(shares, selectedAssetId, onSelect),
        )
        if (showCentre) AllocationCentre(state = state, reduced = reduced)
    }
}

/** One segment in motion: its [share] of the ring, and its [emphasis] (1 when it is selected, -1 when another is, 0 when nothing is). */
private class LiveSlice(val assetId: String, color: Color, share: Float, emphasis: Float) {
    val share = Animatable(share)
    val emphasis = Animatable(emphasis)
    var color by mutableStateOf(color)
}

/**
 * The segments of the ring in motion (section 3.1). A segment is kept by its asset id, so a balance change springs it from where it is and never back to
 * nothing. A new segment grows in, and a segment that leaves shrinks to nothing and then goes. [slices] is in draw order, and a leaving segment keeps its
 * place at the end until it has gone. Only the draw reads these values, so a frame of motion never recomposes the ring.
 */
private class AllocationMotion(shares: List<AllocationShare>, selected: String?) {
    val slices = mutableStateListOf<LiveSlice>()
    val plain = Animatable(if (shares.isEmpty()) 1f else 0f)

    init {
        shares.forEach { slices.add(LiveSlice(it.assetId, it.color, it.fraction, emphasisOf(it.assetId, selected))) }
    }

    /** Moves the segments to [shares] and [selected]. [instant] snaps them, as reduced motion and a hidden allocation do. */
    suspend fun sync(shares: List<AllocationShare>, selected: String?, instant: Boolean) = coroutineScope {
        val kept = slices.associateBy { it.assetId }
        val next = shares.map { share ->
            (kept[share.assetId] ?: LiveSlice(share.assetId, share.color, 0f, 0f)).also { it.color = share.color }
        }
        val leaving = slices.filter { live -> shares.none { it.assetId == live.assetId } }
        slices.clear()
        slices.addAll(next)
        slices.addAll(leaving)
        launch { settle(plain, if (shares.isEmpty()) 1f else 0f, LumenMotion.effects, instant) }
        next.zip(shares).forEach { (live, share) ->
            launch { settle(live.share, share.fraction, LumenMotion.spatial, instant) }
            launch { settle(live.emphasis, emphasisOf(live.assetId, selected), LumenMotion.spatialFast, instant) }
        }
        leaving.forEach { live ->
            launch {
                settle(live.share, 0f, LumenMotion.spatial, instant)
                slices.remove(live)
            }
        }
    }
}

/** Moves [value] to [target] on [spec], from wherever it is, or snaps it when [instant]. */
private suspend fun settle(value: Animatable<Float, AnimationVector1D>, target: Float, spec: SpringSpec<Float>, instant: Boolean) {
    if (instant) value.snapTo(target) else value.animateTo(target, spec)
}

/** The emphasis of the segment [assetId] while [selected] is chosen: 1 for the selected one, -1 for the others, and 0 when nothing is selected. */
private fun emphasisOf(assetId: String, selected: String?): Float = when (selected) {
    null -> 0f
    assetId -> 1f
    else -> -1f
}

/**
 * Draws the ring in one pass: the plain Lumen ring fades as the segments grow in, each segment is an arc with rounded ends, and the dot is drawn last. The
 * cache holds the stroke and the geometry of the size, so a frame allocates no path, brush or stroke. The animated values are read here, in the draw.
 */
private fun Modifier.drawRing(motion: AllocationMotion, background: Color, mark: Color): Modifier = drawWithCache {
    val unit = size.minDimension / BOX
    val radius = RADIUS * unit
    val stroke = Stroke(width = STROKE * unit, cap = StrokeCap.Butt)
    val half = stroke.width / 2f
    val cap = Math.toDegrees(half.toDouble() / radius).toFloat()
    val centre = Offset(size.width / 2f, size.height / 2f)
    val topLeft = Offset(centre.x - radius, centre.y - radius)
    val arc = Size(radius * 2f, radius * 2f)
    val dot = Offset(DOT_X * unit, DOT_Y * unit)
    val dotRadius = DOT_RADIUS * unit
    onDrawBehind {
        val plain = motion.plain.value
        if (plain > 0f) {
            drawArc(
                color = lerp(background, mark, plain),
                startAngle = ARC_START,
                sweepAngle = RING_SWEEP,
                useCenter = false,
                topLeft = topLeft,
                size = arc,
                style = stroke,
            )
        }
        val slices = motion.slices
        AllocationGeometry.layout(slices.size, { slices[it].share.value }) { index, start, span ->
            if (span > 0f) {
                val live = slices[index]
                val emphasis = live.emphasis.value.coerceIn(-1f, 1f)
                val colour = if (emphasis < 0f) lerp(live.color, background, (1f - DIM_KEEP) * -emphasis) else live.color
                val lift = 1f + LIFT * emphasis.coerceAtLeast(0f)
                if (lift > 1f) {
                    drawContext.canvas.save()
                    drawContext.transform.scale(lift, lift, centre)
                }
                drawSegment(colour, start, span, cap, half, radius, centre, topLeft, arc, stroke)
                if (lift > 1f) drawContext.canvas.restore()
            }
        }
        drawCircle(color = mark, radius = dotRadius, center = dot)
    }
}

/**
 * One segment: an arc between its caps, and a round cap at each end. The arc is shortened by the cap, so the caps reach exactly to the segment's ends. A
 * segment too short for an arc is a dot.
 */
private fun DrawScope.drawSegment(
    colour: Color,
    start: Float,
    span: Float,
    cap: Float,
    half: Float,
    radius: Float,
    centre: Offset,
    topLeft: Offset,
    arc: Size,
    stroke: Stroke,
) {
    if (span > 2f * cap) {
        drawArc(
            color = colour,
            startAngle = start + cap,
            sweepAngle = span - 2f * cap,
            useCenter = false,
            topLeft = topLeft,
            size = arc,
            style = stroke,
        )
        drawCircle(color = colour, radius = half, center = pointOn(centre, radius, start + cap))
        drawCircle(color = colour, radius = half, center = pointOn(centre, radius, start + span - cap))
    } else {
        drawCircle(color = colour, radius = half, center = pointOn(centre, radius, start + span / 2f))
    }
}

/** The point on a circle of [radius] about [centre] at [degrees] on the canvas. */
private fun pointOn(centre: Offset, radius: Float, degrees: Float): Offset {
    val radians = Math.toRadians(degrees.toDouble())
    return Offset(centre.x + radius * cos(radians).toFloat(), centre.y + radius * sin(radians).toFloat())
}

/**
 * A tap selects the segment under it, by its angle from the centre, when the tap is on the band of the ring. A tap on the same segment, on the gap, on the
 * dot or in the centre clears the selection. Without [onSelect] the ring takes no taps.
 */
private fun Modifier.tapSegments(shares: List<AllocationShare>, selected: String?, onSelect: ((String?) -> Unit)?): Modifier {
    if (onSelect == null) return this
    return pointerInput(shares, selected) {
        detectTapGestures(
            onTap = { tap ->
                val unit = minOf(size.width, size.height) / BOX
                val dx = tap.x - size.width / 2f
                val dy = tap.y - size.height / 2f
                val band = STROKE * unit / 2f + TAP_SLOP.toPx()
                val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                val onBand = abs(distance - RADIUS * unit) <= band
                val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                val hit = if (onBand) AllocationMath.shareAt(shares, angle)?.let { shares[it].assetId } else null
                onSelect(if (hit == null || hit == selected) null else hit)
            },
        )
    }
}

/** Turns the layer once every [SPIN_MS] while [enabled]: the spinner of section 3.4. The turn is read in the layer, so it never recomposes. */
@Composable
private fun Modifier.spin(enabled: Boolean): Modifier {
    if (!enabled) return this
    val transition = rememberInfiniteTransition(label = "allocation spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "allocation angle",
    )
    return graphicsLayer { rotationZ = angle }
}

/** The total in the centre: whole dollars large, the cents smaller. Hidden, it is the mask. Loading shows nothing, or "Reading…" under reduced motion. */
@Composable
private fun BoxScope.AllocationCentre(state: AllocationState, reduced: Boolean) {
    val place = Modifier.align(Alignment.Center)
    when (state) {
        AllocationState.Loading -> if (reduced) {
            Text(text = "Reading…", modifier = place, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AllocationState.Hidden -> AnimatedAmount(text = "", modifier = place, style = MaterialTheme.typography.headlineSmall, hidden = true)
        else -> {
            val total = if (state is AllocationState.Ready) AllocationMath.total(state.slices) else 0.0
            val text = HomeFormat.usd(total)
            val point = text.lastIndexOf('.')
            Row(modifier = place, verticalAlignment = Alignment.CenterVertically) {
                AnimatedAmount(text = text.substring(0, point), style = MaterialTheme.typography.headlineSmall)
                AnimatedAmount(
                    text = text.substring(point),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** What the ring says to a screen reader. It names the state and the count of assets, never the values. */
private fun describe(state: AllocationState): String = when (state) {
    AllocationState.Loading -> "Reading balances"
    AllocationState.Empty -> "No priced balances"
    AllocationState.Hidden -> "Balances hidden"
    is AllocationState.Ready -> if (state.slices.size == 1) "Allocation of one asset" else "Allocation across ${state.slices.size} assets"
}
