package app.lumio.ui.scan

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.lensly.ui.camera.FrameSource
import app.lensly.ui.camera.hasCameraPermission
import app.lensly.ui.camera.openAppSettings
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.navigation.PickerStop
import app.lensly.ui.qr.QrScanner
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LocalClearAirPalette
import app.lensly.ui.theme.LocalReducedMotion
import app.lensly.ui.theme.LumenMotion
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.rememberLumioHaptics

/** The corner radius of the viewfinder, the ring's width, and the gap between the ring and the camera (section 2.1). */
private val VIEWFINDER_RADIUS = 28.dp
private val RING_WIDTH = 3.dp
private val RING_GAP = 6.dp

/** The height of the viewfinder of a pairing code, as the Clear Air mock draws it (I10). The reply scan passes its own height. */
private val SCAN_FRAME_HEIGHT = 300.dp

/** The corner brackets: how far they sit inside the viewfinder, how long each arm is, and their stroke. */
private val BRACKET_INSET = 16.dp
private val BRACKET_ARM = 22.dp
private val BRACKET_WIDTH = 3.dp

/** One breath of the corner brackets, in milliseconds: they fade between [BREATH_FLOOR] and full (section 2.1). */
private const val BREATH_MS = 1600
private const val BREATH_FLOOR = 0.45f

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
    footer: (@Composable () -> Unit)? = null,
    frameHeight: Dp = SCAN_FRAME_HEIGHT,
    deniedText: String = CAMERA_DENIED_TEXT,
) {
    // The camera is a launch of its own: while the scanner is shown, a policy that keeps own launches unlocked waits on the idle lock.
    DisposableEffect(Unit) {
        PickerStop.hold()
        onDispose { PickerStop.release() }
    }
    val context = LocalContext.current
    // The camera is asked for here, so a refusal shows the Camera Denied note in place of the viewfinder. A test's own frames need no camera.
    var granted by remember { mutableStateOf(frameSource != null || context.hasCameraPermission()) }
    var refused by rememberSaveable { mutableStateOf(false) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { answer ->
        granted = answer
        refused = !answer
    }
    LaunchedEffect(Unit) {
        if (!granted) askCamera.launch(Manifest.permission.CAMERA)
    }
    // Settings may have changed while the app was away, so the permission is read again each time the app comes back.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = context.hasCameraPermission()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LumioScanFrame(
        title = title,
        progress = progress,
        // The camera's hint says what to point it at. While the camera is refused, the note says what is needed instead.
        statusText = if (refused && !granted) "" else statusText,
        complete = complete,
        onCancel = onCancel,
        modifier = modifier,
        footer = footer,
        frameHeight = frameHeight,
    ) { viewport ->
        when {
            granted -> QrScanner(onText = onText, modifier = viewport, frameSource = frameSource)
            refused -> CameraDeniedNote(text = deniedText, onOpenSettings = { context.openAppSettings() }, modifier = viewport)
        }
    }
}

/**
 * The Camera Denied state (K6): a dashed note in place of the viewfinder, in its exact place and size, so the page does not move when the owner
 * allows the camera and the scan starts by itself. Open Settings is the one action, because the system does not ask a second time.
 */
/** The Camera Denied words wherever the camera reads any code. Pair names the code it reads, Lensly's, instead. */
const val CAMERA_DENIED_TEXT = "Lumio needs the camera to read codes. Nothing is recorded."

@Composable
fun CameraDeniedNote(onOpenSettings: () -> Unit, modifier: Modifier = Modifier, text: String = CAMERA_DENIED_TEXT) {
    val palette = LocalClearAirPalette.current
    Column(
        modifier = modifier
            .drawBehind {
                val width = DENIED_DASH_WIDTH.toPx()
                drawRoundRect(
                    color = palette.ink2,
                    topLeft = Offset(width / 2, width / 2),
                    size = Size(size.width - width, size.height - width),
                    cornerRadius = CornerRadius(VIEWFINDER_RADIUS.toPx()),
                    style = Stroke(
                        width = width,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(DENIED_DASH_ON.toPx(), DENIED_DASH_OFF.toPx())),
                    ),
                )
            }
            .padding(ClearAirSpacing.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = ClearAirType.body,
            color = palette.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(ClearAirSpacing.xs))
        Text(
            text = "Allow Camera in Settings, then come back here.",
            style = ClearAirType.label,
            color = palette.ink2,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(ClearAirSpacing.md))
        ClearAirPrimaryButton(text = "Open Settings", onClick = onOpenSettings)
    }
}

/** The dashes of the Camera Denied frame: the stroke, and the length of each dash and of the gap after it. */
private val DENIED_DASH_WIDTH = 1.dp
private val DENIED_DASH_ON = 8.dp
private val DENIED_DASH_OFF = 6.dp

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
    footer: (@Composable () -> Unit)? = null,
    frameHeight: Dp = SCAN_FRAME_HEIGHT,
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
        Box(modifier = Modifier.fillMaxWidth().height(frameHeight)) {
            viewport(Modifier.fillMaxSize().padding(RING_GAP).clip(RoundedCornerShape(VIEWFINDER_RADIUS)))
            ViewfinderCorners(modifier = Modifier.fillMaxSize().padding(RING_GAP))
            PerimeterRing(fraction = if (complete) 1f else progress ?: 0f, modifier = Modifier.fillMaxSize())
            CheckMark(visible = complete, modifier = Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = statusText,
            style = ClearAirType.body,
            color = LocalClearAirPalette.current.ink2,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        footer?.invoke()
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
    val track = LocalClearAirPalette.current.hairline
    val spark = LocalClearAirPalette.current.spark
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
                    drawPath(segment, spark, style = arcStyle)
                }
            }
        },
    )
}

/**
 * The paper corner brackets inside the viewfinder (section 2.1). They breathe between [BREATH_FLOOR] and full while the scan runs, and stay full under
 * reduced motion. The alpha is read in the draw phase, so a breath redraws the brackets without a recomposition.
 */
@Composable
private fun ViewfinderCorners(modifier: Modifier) {
    val paper = LocalClearAirPalette.current.paper
    val breath: State<Float>? = if (LocalReducedMotion.current) {
        null
    } else {
        rememberInfiniteTransition(label = "viewfinder corners").animateFloat(
            initialValue = BREATH_FLOOR,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(BREATH_MS), RepeatMode.Reverse),
            label = "corner breath",
        )
    }
    Box(
        modifier = modifier.drawWithCache {
            val inset = BRACKET_INSET.toPx()
            val arm = BRACKET_ARM.toPx()
            val right = size.width - inset
            val bottom = size.height - inset
            val brackets = Path().apply {
                moveTo(inset, inset + arm)
                lineTo(inset, inset)
                lineTo(inset + arm, inset)
                moveTo(right - arm, inset)
                lineTo(right, inset)
                lineTo(right, inset + arm)
                moveTo(right, bottom - arm)
                lineTo(right, bottom)
                lineTo(right - arm, bottom)
                moveTo(inset + arm, bottom)
                lineTo(inset, bottom)
                lineTo(inset, bottom - arm)
            }
            val style = Stroke(width = BRACKET_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            onDrawBehind {
                drawPath(brackets, paper.copy(alpha = breath?.value ?: 1f), style = style)
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
        ClearAirIcon(glyph = ClearAirGlyph.CHECK, color = LocalClearAirPalette.current.spark, size = CHECK_SIZE)
    }
}
