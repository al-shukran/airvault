package app.lensly.ui.qr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.lensly.core.qr.BinderQrDecodeTransport
import app.lensly.core.qr.LumaFrames
import app.lensly.core.qr.QrDecodeClient
import app.lensly.core.qr.QrDecodeOutcome
import app.lensly.ui.camera.CameraIcons
import app.lensly.ui.camera.FrameSource
import app.lensly.ui.camera.openAppSettings
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceHigh
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Analysis frames are requested at about 720p. Smaller frames decode faster and keep latency low. */
private val ANALYSIS_SIZE = Size(1280, 720)
private val VIEWFINDER_CORNER = 24.dp
private val VIEWFINDER_STROKE = 2.dp
private const val VIEWFINDER_FRACTION = 0.7f
private const val SCRIM_ALPHA = 0.6f

/**
 * Live QR scanner: a CameraX preview with a square viewfinder. The viewfinder dims everything
 * outside it. Frames are decoded for QR codes only, in the QR Decode Sandbox, on a background thread.
 *
 * [onText] runs on the main thread. Identical texts that arrive in a row are reported once, so a
 * code held still does not fire repeatedly. A frame with no code does not reset that check, which
 * stops a flickering read from firing twice. Texts that differ, such as successive UR parts, all fire.
 * If the sandbox does not answer in time, or has died, the viewfinder says that the code could not be read.
 *
 * If the camera permission is missing, the composable asks for it. If it is refused, the
 * composable says so and offers OPEN SETTINGS, which still works after a permanent refusal.
 *
 * [frameSource] is the only thing that bypasses the camera: a test passes a source of generated frames, and then no
 * permission is asked for and no preview is drawn. It is null everywhere in the app, which runs the camera path exactly
 * as it did before the seam existed.
 */
@Composable
fun QrScanner(
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
    frameSource: FrameSource? = null,
) {
    val context = LocalContext.current
    val injected = frameSource
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var refused by rememberSaveable { mutableStateOf(false) }
    var decodeFailed by remember { mutableStateOf(false) }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        refused = !result
    }

    LaunchedEffect(Unit) {
        if (injected == null && !granted) requestPermission.launch(Manifest.permission.CAMERA)
    }

    Box(
        modifier = modifier.background(LenslyBlack),
        contentAlignment = Alignment.Center,
    ) {
        if (injected != null) {
            FixtureDecoder(frameSource = injected, onText = onText, onFailed = { decodeFailed = it })
            ViewfinderOverlay(modifier = Modifier.fillMaxSize())
        } else if (granted) {
            CameraDecoder(onText = onText, onFailed = { decodeFailed = it }, modifier = Modifier.fillMaxSize())
            ViewfinderOverlay(modifier = Modifier.fillMaxSize())
        } else {
            ScanPermissionPrompt(
                refused = refused,
                onAllow = { requestPermission.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { context.openAppSettings() },
            )
        }
        if (decodeFailed) DecodeFailedNotice(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Drops a text when it equals the text before it. It is not thread-safe, so use it from one
 * thread only: the analysis thread.
 */
internal class ConsecutiveDeduper {
    private var last: String? = null

    /** Returns true when [text] differs from the previous accepted text. */
    fun accept(text: String): Boolean {
        if (text == last) return false
        last = text
        return true
    }
}

/**
 * Hands each decode outcome to the scanner on the main thread: a text that differs from the one before, and each change in
 * whether the sandbox can answer. It is not thread-safe, so call [submit] from one thread only.
 */
private class OutcomeSink(
    private val mainExecutor: Executor,
    private val onText: (String) -> Unit,
    private val onFailed: (Boolean) -> Unit,
) {
    private val deduper = ConsecutiveDeduper()
    private var failed = false

    fun submit(outcome: QrDecodeOutcome) {
        val nowFailed = outcome == QrDecodeOutcome.Unavailable
        if (nowFailed != failed) {
            failed = nowFailed
            mainExecutor.execute { onFailed(nowFailed) }
        }
        val text = (outcome as? QrDecodeOutcome.Answered)?.texts?.firstOrNull() ?: return
        if (deduper.accept(text)) mainExecutor.execute { onText(text) }
    }
}

@Composable
private fun CameraDecoder(
    onText: (String) -> Unit,
    onFailed: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnText = rememberUpdatedState(onText)
    val latestOnFailed = rememberUpdatedState(onFailed)
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val client = QrDecodeClient(BinderQrDecodeTransport(context))
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        var disposed = false

        providerFuture.addListener({
            // The listener can run after the composable left, so do nothing in that case.
            if (disposed) return@addListener
            val cameraProvider = providerFuture.get()
            provider = cameraProvider

            val sink = OutcomeSink(
                mainExecutor = mainExecutor,
                onText = { text -> if (!disposed) latestOnText.value(text) },
                onFailed = { failed -> if (!disposed) latestOnFailed.value(failed) },
            )
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                ANALYSIS_SIZE,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        )
                        .build(),
                )
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            // The luminance is copied out and the image closed before the decode, so the camera buffer is not held during the
            // call. The analyzer waits for the decode, and KEEP_ONLY_LATEST drops the frames that arrive meanwhile, so one
            // frame is in flight at a time.
            imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                val width = image.width
                val height = image.height
                val luma = try {
                    image.lumaBytes()
                } finally {
                    image.close()
                }
                sink.submit(client.decode(luma, width, height))
            }
            analysis = imageAnalysis

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis,
            )
        }, mainExecutor)

        onDispose {
            disposed = true
            analysis?.clearAnalyzer()
            provider?.unbindAll()
            analysisExecutor.shutdown()
            client.close()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** The luminance of a camera frame. The analysis format is YUV_420_888, whose first plane is the luminance. */
private fun ImageProxy.lumaBytes(): ByteArray {
    val plane = planes[0]
    return LumaFrames.fromPlane(plane.buffer, width, height, plane.rowStride, plane.pixelStride)
}

/** Dims the frame outside a centred square with rounded corners, and outlines the square. */
@Composable
private fun ViewfinderOverlay(modifier: Modifier) {
    Canvas(
        modifier = modifier.graphicsLayer {
            // Offscreen compositing lets BlendMode.Clear cut the window out of the scrim.
            compositingStrategy = CompositingStrategy.Offscreen
        },
    ) {
        val side = minOf(size.width, size.height) * VIEWFINDER_FRACTION
        val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
        val square = GeometrySize(side, side)
        val corner = CornerRadius(VIEWFINDER_CORNER.toPx())

        drawRect(color = Color.Black.copy(alpha = SCRIM_ALPHA))
        drawRoundRect(
            color = Color.Transparent,
            topLeft = topLeft,
            size = square,
            cornerRadius = corner,
            blendMode = BlendMode.Clear,
        )
        drawRoundRect(
            color = LenslyOnSurface,
            topLeft = topLeft,
            size = square,
            cornerRadius = corner,
            style = Stroke(width = VIEWFINDER_STROKE.toPx()),
        )
    }
}

/**
 * The error state of the scanner. It shows while the sandbox cannot answer, because it timed out or died, and it goes away
 * on the next frame the sandbox answers.
 */
@Composable
private fun DecodeFailedNotice(modifier: Modifier) {
    Text(
        text = "Couldn't read that code",
        modifier = modifier
            .padding(LenslySpacing.md)
            .background(LenslySurfaceHigh, RoundedCornerShape(percent = 50))
            .padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.sm)
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = LenslyOnSurface,
    )
}

/**
 * Shown in place of the scanner until camera access is granted. Before any refusal it asks for access. After a refusal
 * ([refused]) it says access is off and puts OPEN SETTINGS first, because the system prompt may not show again.
 */
@Composable
fun ScanPermissionPrompt(
    refused: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(LenslySpacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = CameraIcons.Camera,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = if (refused) "CAMERA IS OFF" else "CAMERA ACCESS",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            text = if (refused) {
                "Turn on camera access in Settings to scan codes. Images never leave the phone."
            } else {
                "Lensly reads QR codes with the camera. Images never leave the phone."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(LenslySpacing.xl))
        if (refused) {
            PillButton(
                text = "Open settings",
                onClick = onOpenSettings,
                accent = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(LenslySpacing.sm))
            PillButton(text = "Allow camera", onClick = onAllow, modifier = Modifier.fillMaxWidth())
        } else {
            PillButton(
                text = "Allow camera",
                onClick = onAllow,
                accent = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Decodes every frame [frameSource] emits. This runs only when a source is passed in, so the production path with no
 * source never enters it. Its frames go through the same QR Decode Sandbox as the camera's, and a text is reported once,
 * on the main thread.
 */
@Composable
private fun FixtureDecoder(
    frameSource: FrameSource,
    onText: (String) -> Unit,
    onFailed: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val latestOnText = rememberUpdatedState(onText)
    val latestOnFailed = rememberUpdatedState(onFailed)

    DisposableEffect(frameSource) {
        val decoderExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val client = QrDecodeClient(BinderQrDecodeTransport(context))
        var disposed = false
        val sink = OutcomeSink(
            mainExecutor = mainExecutor,
            onText = { text -> if (!disposed) latestOnText.value(text) },
            onFailed = { failed -> if (!disposed) latestOnFailed.value(failed) },
        )
        val handle = frameSource.start { bitmap ->
            // The pixels are read on the emitting thread, while the bitmap is valid. The frames are decoded in order.
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val luma = LumaFrames.fromArgb(pixels, width, height)
            decoderExecutor.execute { sink.submit(client.decode(luma, width, height)) }
        }

        onDispose {
            disposed = true
            handle.stop()
            decoderExecutor.shutdown()
            client.close()
        }
    }
}
