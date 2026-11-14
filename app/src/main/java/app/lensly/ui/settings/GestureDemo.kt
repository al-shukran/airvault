package app.lensly.ui.settings

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyMuted
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutline
import app.lensly.ui.theme.LenslyOutlineInteractive
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceContainer
import app.lensly.ui.theme.LenslySurfaceHigh
import java.util.Locale

/** One full loop of a demo, in milliseconds: the gesture, then a short rest before it starts again. */
private const val CYCLE_MS = 3_200

/** How long a touch-down ripple takes to spread and fade. */
private const val RIPPLE_MS = 450

/** How much of a finger's path the trail keeps, so a swipe leaves a short line behind it. */
private const val TRAIL_MS = 300

/** Radius of a finger, and the largest radius a ripple reaches, as fractions of the frame's shorter side. */
private const val FINGER_RADIUS = 0.045f
private const val RIPPLE_RADIUS = 0.16f

/** The frame is a phone held upright: 9 wide by 19.5 tall, and at most this wide so a demo card stays compact. */
private val FRAME_MAX_WIDTH = 240.dp
private const val FRAME_ASPECT = 9f / 19.5f

// The space between the frame's edge and the camera UI, as a fraction of the frame's shorter side. It keeps every icon
// clear of the edge, and is the same at the top and the bottom, so the shutter row sits close to the bottom edge.
private const val FRAME_INSET = 0.06f

// Places in the card, as fractions of its width (x) and height (y).
private val CENTRE = Offset(0.5f, 0.40f) // the viewfinder, where the gestures are made
private val SHUTTER = Offset(0.5f, 0.90f)
private val THUMBNAIL = Offset(0.25f, 0.90f)
private val SWITCH = Offset(0.75f, 0.90f)
private val SIDE = Offset(0.09f, 0f) // half the gap between two fingers that land side by side
private val PINCH_START = Offset(0.05f, 0f) // each pinch finger starts this far from the centre
private val PINCH_END = Offset(0.10f, 0f) // and ends this far, so the span doubles: 1x to 2x

/**
 * The thumbnail swipe, as a fraction of the width. The portrait frame is 420 px wide, so the finger moves a little over the
 * 40 px that the swipe detector reads as a completed swipe.
 */
private const val SWIPE = 0.098f

/** A finger of a demo. It lands at [downMs], lifts at [upMs], and is at [at] in between. */
internal class DemoFinger(val downMs: Int, val upMs: Int, val at: (Int) -> Offset)

/** A whole demo, in milliseconds of the cycle: its fingers, and the moment the gesture completes in the app. */
internal class GestureScript(val fingers: List<DemoFinger>, val completeMs: Int)

/** The steps of [this] gesture in words, shown under its demo. */
internal val EntryGesture.demoCaption: String
    get() = when (this) {
        EntryGesture.DOUBLE_TAP_HOLD -> "Tap twice with two fingers, then press and hold."
        EntryGesture.THUMBNAIL_HOLD_SWIPE -> "Press and hold the gallery thumbnail, then swipe left."
        EntryGesture.PINCH_TRIPLE_TAP -> "Pinch to 2x, lift, then tap the viewfinder three times."
        EntryGesture.SWITCH_THEN_SHUTTER_HOLD -> "Tap switch camera three times, then press and hold the shutter."
    }

/**
 * The fingers of [gesture] with the timing the detectors in ui/camera need. The timing is checked against them in
 * GestureDemoTimingTest.
 */
internal fun gestureScript(gesture: EntryGesture): GestureScript = when (gesture) {
    // A tap, a second tap, then a press that is held for 1.2 s. The hold completes while both fingers are still down.
    EntryGesture.DOUBLE_TAP_HOLD -> GestureScript(
        fingers = twoFingers(250, 450) + twoFingers(600, 800) + twoFingers(950, 2_400),
        completeMs = 2_150,
    )
    // The finger rests on the thumbnail for 0.9 s, slides left, then lifts. The swipe completes as it reaches its length.
    EntryGesture.THUMBNAIL_HOLD_SWIPE -> GestureScript(
        fingers = listOf(DemoFinger(250, 2_000, slide(THUMBNAIL, THUMBNAIL - Offset(SWIPE, 0f), 1_150, 1_650))),
        completeMs = 1_650,
    )
    // Two fingers spread from 1x to 2x and lift together, then three quick taps on the viewfinder.
    EntryGesture.PINCH_TRIPLE_TAP -> GestureScript(
        fingers = listOf(
            DemoFinger(250, 1_150, slide(CENTRE - PINCH_START, CENTRE - PINCH_END, 250, 1_050)),
            DemoFinger(250, 1_150, slide(CENTRE + PINCH_START, CENTRE + PINCH_END, 250, 1_050)),
            tap(CENTRE, 1_350, 1_530),
            tap(CENTRE, 1_650, 1_830),
            tap(CENTRE, 1_950, 2_130),
        ),
        completeMs = 2_130,
    )
    // Three taps on the camera switch, then the shutter is held for 0.8 s and completes while it is still down.
    EntryGesture.SWITCH_THEN_SHUTTER_HOLD -> GestureScript(
        fingers = listOf(
            tap(SWITCH, 300, 450),
            tap(SWITCH, 650, 800),
            tap(SWITCH, 1_000, 1_150),
            tap(SHUTTER, 1_400, 2_300),
        ),
        completeMs = 2_200,
    )
}

/**
 * The demo of [gesture]: the camera drawn in outline, with a finger that makes the gesture on a loop, then the steps in
 * words. The loop runs only while the demo is inside the window, so a demo scrolled away costs nothing. With animations
 * turned off in system settings, it shows the frame at which the gesture completes instead.
 */
@Composable
internal fun GestureDemo(gesture: EntryGesture, modifier: Modifier = Modifier) {
    val script = remember(gesture) { gestureScript(gesture) }
    val context = LocalContext.current
    val reduceMotion = remember(context) { animatorsDisabled(context) }
    val windowSize = LocalWindowInfo.current.containerSize
    var onScreen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(LenslyRadius.md)
    Column(
        modifier = modifier.testTag("gesture-demo-${gesture.storedId}"),
        verticalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .widthIn(max = FRAME_MAX_WIDTH)
                    .fillMaxWidth()
                    .aspectRatio(FRAME_ASPECT)
                    .clip(shape)
                    .background(LenslyBlack)
                    .border(1.dp, LenslyOutline, shape)
                    .onGloballyPositioned { coords ->
                        val window = Rect(Offset.Zero, Size(windowSize.width.toFloat(), windowSize.height.toFloat()))
                        onScreen = window.overlaps(coords.boundsInWindow())
                    }
                    .clearAndSetSemantics {}, // the caption below says the same in words
            ) {
                if (reduceMotion || !onScreen) {
                    DemoCanvas(script, gesture) { script.completeMs }
                } else {
                    LoopingDemo(script, gesture)
                }
            }
        }
        Text(
            text = gesture.demoCaption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** True when the system has animations turned off: the animator duration scale is 0. */
internal fun animatorsDisabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** The demo running its loop. The time is read in the draw phase, so a frame redraws the canvas without recomposing. */
@Composable
private fun LoopingDemo(script: GestureScript, gesture: EntryGesture) {
    val time = rememberInfiniteTransition(label = "gesture-demo").animateFloat(
        initialValue = 0f,
        targetValue = CYCLE_MS.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "gesture-demo-time",
    )
    DemoCanvas(script, gesture) { time.value.toInt() }
}

@Composable
private fun DemoCanvas(script: GestureScript, gesture: EntryGesture, timeMs: () -> Int) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = LenslyMuted)
    val zoomStyle = MaterialTheme.typography.labelMedium.copy(color = LenslyMuted)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val ms = timeMs()
        drawViewfinder(measurer, labelStyle)
        if (gesture == EntryGesture.PINCH_TRIPLE_TAP) drawZoom(measurer, zoomStyle, pinchZoomAt(ms))
        for (finger in script.fingers) drawFinger(finger, ms)
    }
}

/** The camera in outline: the frame, the mode label, the gallery thumbnail, the shutter and the camera switch. */
private fun DrawScope.drawViewfinder(measurer: TextMeasurer, labelStyle: TextStyle) {
    val unit = size.minDimension
    val inset = unit * FRAME_INSET
    drawRoundRect(
        color = LenslyOutlineInteractive,
        topLeft = Offset(inset, inset),
        size = Size(size.width - 2 * inset, size.height - 2 * inset),
        cornerRadius = CornerRadius(unit * 0.1f),
        style = Stroke(width = 1.dp.toPx()),
    )
    val label = measurer.measure("Photo", labelStyle)
    val padX = 8.dp.toPx()
    val padY = 3.dp.toPx()
    val chipTopLeft = Offset(inset * 2, inset * 2)
    drawRoundRect(
        color = LenslySurfaceContainer,
        topLeft = chipTopLeft,
        size = Size(label.size.width + 2 * padX, label.size.height + 2 * padY),
        cornerRadius = CornerRadius(unit),
    )
    drawText(label, topLeft = chipTopLeft + Offset(padX, padY))

    val thumb = cardPoint(THUMBNAIL)
    val thumbSide = unit * 0.14f
    drawRoundRect(
        color = LenslySurfaceHigh,
        topLeft = thumb - Offset(thumbSide / 2, thumbSide / 2),
        size = Size(thumbSide, thumbSide),
        cornerRadius = CornerRadius(unit * 0.03f),
    )
    drawRoundRect(
        color = LenslyMuted,
        topLeft = thumb - Offset(thumbSide / 2, thumbSide / 2),
        size = Size(thumbSide, thumbSide),
        cornerRadius = CornerRadius(unit * 0.03f),
        style = Stroke(width = 1.dp.toPx()),
    )

    val shutter = cardPoint(SHUTTER)
    drawCircle(color = LenslyMuted, radius = unit * 0.11f, center = shutter, style = Stroke(width = 2.dp.toPx()))
    drawCircle(color = LenslyOnSurface, radius = unit * 0.055f, center = shutter)

    drawCircle(
        color = LenslyMuted,
        radius = unit * 0.075f,
        center = cardPoint(SWITCH),
        style = Stroke(width = 1.5.dp.toPx()),
    )
}

/** The zoom readout, above the centre, as the practice pad shows it. */
private fun DrawScope.drawZoom(measurer: TextMeasurer, style: TextStyle, zoom: Float) {
    val text = measurer.measure(String.format(Locale.US, "%.1fx", zoom), style)
    drawText(
        text,
        topLeft = Offset(size.width / 2 - text.size.width / 2f, size.height * 0.2f - text.size.height / 2f),
    )
}

/** One finger: a dot while it is down, a ripple from where it landed, and a trail behind it while it moves. */
private fun DrawScope.drawFinger(finger: DemoFinger, ms: Int) {
    val age = ms - finger.downMs
    val unit = size.minDimension
    if (age in 0 until RIPPLE_MS) {
        val progress = age.toFloat() / RIPPLE_MS
        val radius = unit * (FINGER_RADIUS + (RIPPLE_RADIUS - FINGER_RADIUS) * progress)
        drawCircle(
            color = LenslyAccent.copy(alpha = 0.6f * (1f - progress)),
            radius = radius,
            center = cardPoint(finger.at(finger.downMs)),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
    if (ms in finger.downMs until finger.upMs) {
        val now = cardPoint(finger.at(ms))
        val earlier = cardPoint(finger.at(maxOf(finger.downMs, ms - TRAIL_MS)))
        if (earlier != now) {
            drawLine(
                color = LenslyAccent.copy(alpha = 0.5f),
                start = earlier,
                end = now,
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        drawCircle(color = LenslyAccent, radius = unit * FINGER_RADIUS, center = now)
    }
}

/** A point of the card in pixels, from its fractions. */
private fun DrawScope.cardPoint(fraction: Offset): Offset = Offset(fraction.x * size.width, fraction.y * size.height)

/** The zoom at [ms] of the pinch demo: 1x until the spread starts, 2x once it has ended. */
private fun pinchZoomAt(ms: Int): Float = 1f + ((ms - 250) / 800f).coerceIn(0f, 1f)

private fun tap(at: Offset, downMs: Int, upMs: Int) = DemoFinger(downMs, upMs, still(at))

private fun twoFingers(downMs: Int, upMs: Int): List<DemoFinger> =
    listOf(tap(CENTRE - SIDE, downMs, upMs), tap(CENTRE + SIDE, downMs, upMs))

private fun still(at: Offset): (Int) -> Offset = { at }

/** A finger that rests at [from], moves in a straight line to [to] between [startMs] and [endMs], then rests at [to]. */
private fun slide(from: Offset, to: Offset, startMs: Int, endMs: Int): (Int) -> Offset = { ms ->
    val progress = ((ms - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
    from + (to - from) * progress
}
