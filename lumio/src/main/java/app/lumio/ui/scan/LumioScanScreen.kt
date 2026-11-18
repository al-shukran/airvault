package app.lumio.ui.scan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lensly.ui.camera.FrameSource
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.navigation.PickerStop
import app.lensly.ui.qr.QrScanner
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LumenMotion
import app.lumio.ui.kit.rememberLumioHaptics

/** The corner radius of the viewfinder, the ring's width, and the gap between the ring and the camera (section 2.1). */
private val VIEWFINDER_RADIUS = 28.dp
private val RING_WIDTH = 3.dp
private val RING_GAP = 6.dp

/** The check that closes a completed code. */
private val CHECK_SIZE = 56.dp

/** A tick is at least this far from the last one, in milliseconds (section 3.5). */
private const val TICK_GAP_MS = 80L

/**
 * The scan of a code (section 2.1): [title] in the shell, the live camera in a rounded viewfinder, and [statusText] under it. There is no button: back
 * cancels. [progress] is how much of an animated code is in, as a fraction, or null for a single code. The ring hugs the viewfinder and springs to
 * each new part, with a tick per part. [complete] closes the ring, shows a check and gives the success haptic.
 *
 * [frameSource] is passed to [QrScanner]. Leave it null on a device, where the camera feeds the scanner. A test passes its own source of frames.
 */
@Composable
fun LumioScanScreen(
    title: String,
    progress: Float?,
    statusText: String,
    onText: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    complete: Boolean = false,
    frameSource: FrameSource? = null,
) {
    // The camera is a launch of its own: while the scanner is shown, a policy that keeps own launches unlocked waits on the idle lock.
    DisposableEffect(Unit) {
        PickerStop.hold()
        onDispose { PickerStop.release() }
    }
    LumioScanFrame(
        title = title,
        progress = progress,
        statusText = statusText,
        complete = complete,
        onCancel = onCancel,
        modifier = modifier,
    ) { viewport ->
        QrScanner(onText = onText, modifier = viewport, frameSource = frameSource)
    }
}

/**
 * The scan layout around a viewport: the shell, the ring, the check and the status line. [LumioScanScreen] puts the live scanner in [viewport]. The
 * screenshot tests put a placeholder there, because a camera feed cannot be rendered off the device.
 */
@Composable
fun LumioScanFrame(
    title: String,
    progress: Float?,
    statusText: String,
    complete: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewport: @Composable (Modifier) -> Unit,
) {
    val haptics = rememberLumioHaptics()
    var lastTickMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(progress) {
        if (progress != null && !complete) {
            val now = System.currentTimeMillis()
            if (now - lastTickMs >= TICK_GAP_MS) {
                haptics.tick()
                lastTickMs = now
            }
        }
    }
    LaunchedEffect(complete) {
        if (complete) haptics.success()
    }
    LenslyScreen(title = title, modifier = modifier, onBack = onCancel, scrollable = false) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            viewport(Modifier.fillMaxSize().padding(RING_GAP).clip(RoundedCornerShape(VIEWFINDER_RADIUS)))
            PerimeterRing(fraction = if (complete) 1f else progress ?: 0f, modifier = Modifier.fillMaxSize())
            CheckMark(visible = complete, modifier = Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(LenslySpacing.md))
    }
}

/**
 * The ring that hugs the viewfinder: a faint track all the way round, and the arc of the code that has been read. The arc springs to each new part
 * (section 3.1). It is read in the draw phase, so a new part redraws the ring without a recomposition.
 */
@Composable
private fun PerimeterRing(fraction: Float, modifier: Modifier) {
    val arc by animateFloatAsState(targetValue = fraction.coerceIn(0f, 1f), animationSpec = LumenMotion.spatial, label = "scan ring")
    val track = MaterialTheme.colorScheme.outlineVariant
    val gold = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier.drawWithCache {
            val width = RING_WIDTH.toPx()
            val inset = width / 2f
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        Rect(inset, inset, size.width - inset, size.height - inset),
                        CornerRadius((VIEWFINDER_RADIUS + RING_GAP).toPx()),
                    ),
                )
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val length = measure.length
            val segment = Path()
            val trackStyle = Stroke(width = width)
            val arcStyle = Stroke(width = width, cap = StrokeCap.Round)
            onDrawBehind {
                drawPath(path, track, style = trackStyle)
                if (arc > 0f) {
                    segment.reset()
                    measure.getSegment(0f, length * arc, segment, true)
                    drawPath(segment, gold, style = arcStyle)
                }
            }
        },
    )
}

/** The check that closes a completed code. It pops in on the bouncy spring, and fades when the screen leaves. */
@Composable
private fun BoxScope.CheckMark(visible: Boolean, modifier: Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = scaleIn(LumenMotion.bouncy, initialScale = 0.6f) + fadeIn(LumenMotion.effects),
        exit = fadeOut(LumenMotion.effects),
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(CHECK_SIZE),
        )
    }
}
