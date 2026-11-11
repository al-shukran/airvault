package app.lensly.ui.camera

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.ErrorState
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.pressFeedback
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyMuted
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutlineInteractive
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceHigh
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long a failed capture stays on screen, in milliseconds. */
private const val CAPTURE_NOTICE_MS = 3500L

/** The white wash over the preview when a photo is saved: its peak opacity and how long it takes to fade. */
private const val SHUTTER_FLASH_ALPHA = 0.7f
private const val SHUTTER_FLASH_MS = 220

/**
 * The camera face of the app: a plain photo viewfinder. [onHiddenEntry] fires when the
 * viewfinder receives the hidden entry gesture; nothing on screen reveals it.
 */
@Composable
fun CameraScreen(
    onOpenGallery: () -> Unit,
    onHiddenEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result -> granted = result }

    // Settings may have changed while the app was away, so re-check whenever it resumes.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = context.hasCameraPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) {
        val store = remember(context) { PhotoStore.of(context) }
        CameraViewfinder(
            store = store,
            onOpenGallery = onOpenGallery,
            onHiddenEntry = onHiddenEntry,
            modifier = modifier,
        )
    } else {
        CameraPermissionRationale(
            canOpenSettings = requested,
            onAllow = {
                requested = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onOpenSettings = { context.openAppSettings() },
            modifier = modifier,
        )
    }
}

@Composable
private fun CameraViewfinder(
    store: PhotoStore,
    onOpenGallery: () -> Unit,
    onHiddenEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val controller = remember { CameraController() }
    val detector = remember { HiddenGestureDetector() }
    val currentOnHiddenEntry by rememberUpdatedState(onHiddenEntry)

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var lensFacing by rememberSaveable { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashOn by rememberSaveable { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var bindAttempt by remember { mutableIntStateOf(0) }
    var photoVersion by remember { mutableIntStateOf(0) }
    var captureFailures by remember { mutableIntStateOf(0) }
    var showCaptureFailure by remember { mutableStateOf(false) }
    val shutterFlash = remember { Animatable(0f) }
    var viewfinderBounds by remember { mutableStateOf<Rect?>(null) }
    val currentBounds by rememberUpdatedState(viewfinderBounds)

    val latestPhoto by produceState<File?>(initialValue = null, photoVersion) {
        value = withContext(Dispatchers.IO) { store.list().firstOrNull() }
    }

    LaunchedEffect(previewView, lensFacing, bindAttempt) {
        val view = previewView ?: return@LaunchedEffect
        val selector = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        controller.flashEnabled = flashOn
        if (controller.bind(context, lifecycleOwner, view, selector)) {
            hasFlash = controller.hasFlashUnit
            unavailable = false
        } else if (lensFacing != CameraSelector.LENS_FACING_BACK) {
            // This device has no such lens. Fall back to the back camera.
            lensFacing = CameraSelector.LENS_FACING_BACK
        } else {
            // The back camera cannot be bound, for example because another app holds it.
            unavailable = true
        }
    }

    // Each failed capture restarts the notice, so a second failure shows it again for the full time.
    LaunchedEffect(captureFailures) {
        if (captureFailures > 0) {
            showCaptureFailure = true
            delay(CAPTURE_NOTICE_MS)
            showCaptureFailure = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LenslyBlack)
            .pointerInput(Unit) {
                detectViewfinderGestures(
                    detector = detector,
                    viewfinderBounds = { currentBounds },
                    onFocus = { position -> controller.focusAt(position.x, position.y) },
                    onZoom = { scale -> controller.zoomBy(scale) },
                    onHiddenEntry = { currentOnHiddenEntry() },
                )
            },
    ) {
        AndroidView(
            factory = { viewContext ->
                PreviewView(viewContext)
                    .apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
                    .also { previewView = it }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (unavailable) {
            CameraUnavailable(
                onRetry = { bindAttempt++ },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ViewfinderOverlay(
                flashAvailable = hasFlash,
                flashOn = flashOn,
                onToggleFlash = {
                    flashOn = !flashOn
                    controller.flashEnabled = flashOn
                },
                showCaptureFailure = showCaptureFailure,
                latestPhoto = latestPhoto,
                onOpenGallery = onOpenGallery,
                onShutter = {
                    controller.takePhoto(
                        file = store.newFile(),
                        executor = ContextCompat.getMainExecutor(context),
                        onSaved = {
                            photoVersion++
                            scope.launch {
                                shutterFlash.snapTo(SHUTTER_FLASH_ALPHA)
                                shutterFlash.animateTo(0f, tween(SHUTTER_FLASH_MS))
                            }
                        },
                        onFailure = { captureFailures++ },
                    )
                },
                onFlip = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        CameraSelector.LENS_FACING_BACK
                    } else {
                        CameraSelector.LENS_FACING_FRONT
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onViewfinderBounds = { viewfinderBounds = it },
            )
        }
        // A white wash over the preview confirms each saved photo. It is transparent between shots and lets touches through.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = shutterFlash.value)),
        )
    }
}

/**
 * The viewfinder controls: the mode chip and flash toggle on top, the gallery thumbnail, shutter and camera switch at
 * the bottom. A failed capture shows a notice under the top bar. The space between is the touch area for focus and
 * zoom, and [onViewfinderBounds] reports it.
 */
@Composable
internal fun ViewfinderOverlay(
    flashAvailable: Boolean,
    flashOn: Boolean,
    onToggleFlash: () -> Unit,
    showCaptureFailure: Boolean,
    latestPhoto: File?,
    onOpenGallery: () -> Unit,
    onShutter: () -> Unit,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier,
    onViewfinderBounds: (Rect) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        ViewfinderTopBar(
            flashAvailable = flashAvailable,
            flashOn = flashOn,
            onToggleFlash = onToggleFlash,
        )
        if (showCaptureFailure) {
            CaptureFailedBanner()
        }
        Spacer(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    // The Column fills the root box, so bounds relative to the Column match pointer coordinates.
                    val origin = coords.parentLayoutCoordinates?.localPositionOf(coords, Offset.Zero)
                    onViewfinderBounds(
                        Rect(
                            offset = origin ?: Offset.Zero,
                            size = Size(coords.size.width.toFloat(), coords.size.height.toFloat()),
                        ),
                    )
                },
        )
        ViewfinderBottomBar(
            latestPhoto = latestPhoto,
            onOpenGallery = onOpenGallery,
            onShutter = onShutter,
            onFlip = onFlip,
        )
    }
}

/** Notice under the top bar after a photo could not be saved. It sits on black, so it reads over any preview. */
@Composable
internal fun CaptureFailedBanner(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.xs)
            .clip(RoundedCornerShape(LenslyRadius.md))
            .background(LenslyBlack),
    ) {
        InlineBanner(message = "Photo not saved. Try again.", tone = BannerTone.Error)
    }
}

/** Shown in place of the viewfinder when the back camera cannot be opened. RETRY binds it again. */
@Composable
internal fun CameraUnavailable(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(LenslyBlack)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        ErrorState(
            icon = CameraIcons.Camera,
            title = "Camera unavailable",
            body = "Another app may be using the camera. Close it, then try again.",
            actionLabel = "Try again",
            onAction = onRetry,
        )
    }
}

@Composable
private fun ViewfinderTopBar(
    flashAvailable: Boolean,
    flashOn: Boolean,
    onToggleFlash: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillChip(text = "Photo")
        Spacer(modifier = Modifier.weight(1f))
        if (flashAvailable) {
            RoundControl(
                size = 44.dp,
                contentDescription = if (flashOn) "Flash on" else "Flash off",
                onClick = onToggleFlash,
            ) {
                Icon(
                    imageVector = if (flashOn) CameraIcons.FlashOn else CameraIcons.FlashOff,
                    contentDescription = null,
                    tint = if (flashOn) LenslyOnSurface else LenslyMuted,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun ViewfinderBottomBar(
    latestPhoto: File?,
    onOpenGallery: () -> Unit,
    onShutter: () -> Unit,
    onFlip: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = LenslySpacing.xl, vertical = LenslySpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        GalleryThumbnail(latestPhoto = latestPhoto, onClick = onOpenGallery)
        ShutterButton(onClick = onShutter)
        RoundControl(
            size = 56.dp,
            contentDescription = "Switch camera",
            onClick = onFlip,
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                tint = LenslyOnSurface,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun GalleryThumbnail(latestPhoto: File?, onClick: () -> Unit) {
    val haptics = rememberKeyHaptics()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(56.dp)
            .pressFeedback(interaction, enabled = true)
            .clip(CircleShape)
            .border(1.dp, LenslyOutlineInteractive, CircleShape)
            .tapHaptic(haptics)
            .clickable(
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = "Open gallery" },
    ) {
        if (latestPhoto != null) {
            PhotoImage(
                file = latestPhoto,
                maxSidePx = 240,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ShutterButton(onClick: () -> Unit) {
    val haptics = rememberKeyHaptics()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(80.dp)
            .pressFeedback(interaction, enabled = true)
            .clip(CircleShape)
            .background(LenslyOnSurface)
            .tapHaptic(haptics)
            .clickable(
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = "Take photo" },
    )
}

@Composable
private fun RoundControl(
    size: Dp,
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = rememberKeyHaptics()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(size)
            .pressFeedback(interaction, enabled = true)
            .clip(CircleShape)
            .background(LenslySurfaceHigh)
            .tapHaptic(haptics)
            .clickable(
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
