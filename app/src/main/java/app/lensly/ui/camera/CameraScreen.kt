package app.lensly.ui.camera

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.lensly.ui.components.PillChip
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyMuted
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutline
import app.lensly.ui.theme.LenslySurfaceHigh
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val controller = remember { CameraController() }
    val detector = remember { HiddenGestureDetector() }
    val currentOnHiddenEntry by rememberUpdatedState(onHiddenEntry)

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var lensFacing by rememberSaveable { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashOn by rememberSaveable { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var photoVersion by remember { mutableIntStateOf(0) }
    var viewfinderBounds by remember { mutableStateOf<Rect?>(null) }
    val currentBounds by rememberUpdatedState(viewfinderBounds)

    val latestPhoto by produceState<File?>(initialValue = null, photoVersion) {
        value = withContext(Dispatchers.IO) { store.list().firstOrNull() }
    }

    LaunchedEffect(previewView, lensFacing) {
        val view = previewView ?: return@LaunchedEffect
        val selector = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        controller.flashEnabled = flashOn
        if (controller.bind(context, lifecycleOwner, view, selector)) {
            hasFlash = controller.hasFlashUnit
        } else if (lensFacing != CameraSelector.LENS_FACING_BACK) {
            // This device has no such lens. Fall back to the back camera.
            lensFacing = CameraSelector.LENS_FACING_BACK
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
        Column(modifier = Modifier.fillMaxSize()) {
            ViewfinderTopBar(
                flashAvailable = hasFlash,
                flashOn = flashOn,
                onToggleFlash = {
                    flashOn = !flashOn
                    controller.flashEnabled = flashOn
                },
            )
            // The middle of the screen is the touch area for focus and zoom. Controls sit above and below it.
            Spacer(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        // The Column fills the root box, so bounds relative to the Column match pointer coordinates.
                        val origin = coords.parentLayoutCoordinates?.localPositionOf(coords, Offset.Zero)
                        viewfinderBounds = Rect(
                            offset = origin ?: Offset.Zero,
                            size = Size(coords.size.width.toFloat(), coords.size.height.toFloat()),
                        )
                    },
            )
            ViewfinderBottomBar(
                latestPhoto = latestPhoto,
                onOpenGallery = onOpenGallery,
                onShutter = {
                    controller.takePhoto(store.newFile(), ContextCompat.getMainExecutor(context)) {
                        photoVersion++
                    }
                },
                onFlip = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        CameraSelector.LENS_FACING_BACK
                    } else {
                        CameraSelector.LENS_FACING_FRONT
                    }
                },
            )
        }
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
            .padding(horizontal = 32.dp, vertical = 24.dp),
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
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .border(1.dp, LenslyOutline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
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
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .background(LenslyOnSurface)
            .clickable(role = Role.Button, onClick = onClick)
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
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(LenslySurfaceHigh)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
