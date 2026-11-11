package app.lensly.ui.camera

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyMuted
import java.util.Locale

/** The lowest and highest zoom the practice surface simulates. */
private const val PAD_MIN_ZOOM = 1f
private const val PAD_MAX_ZOOM = 5f

/**
 * The practice surface for one entry gesture: the camera's layout with no camera behind it. It runs the same gesture code as
 * the camera, and [onPerformed] runs once, when the gesture completes here. The pinch zoom is simulated, and its level is
 * shown, so the owner can find the target level while practising. The camera itself shows no level.
 */
@Composable
internal fun EntryGesturePad(
    gesture: EntryGesture,
    onPerformed: () -> Unit,
    onShutterClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val slopPx = LocalViewConfiguration.current.touchSlop
    val swipePx = with(LocalDensity.current) { ENTRY_SWIPE_DISTANCE.toPx() }
    val touch = remember(slopPx, swipePx) { EntryTouch(slopPx, swipePx) }
    SideEffect { touch.select(gesture) }
    val currentOnPerformed by rememberUpdatedState(onPerformed)
    var zoom by remember { mutableFloatStateOf(1f) }
    var performed by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(CameraMode.PHOTO) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LenslyBlack)
            .onGloballyPositioned { touch.root = it }
            .pointerInput(touch) {
                detectEntryGestures(
                    touch = touch,
                    zoomRatio = { zoom },
                    onFocus = {},
                    onZoom = { scale -> zoom = (zoom * scale).coerceIn(PAD_MIN_ZOOM, PAD_MAX_ZOOM) },
                    onHiddenEntry = {
                        if (!performed) {
                            performed = true
                            currentOnPerformed()
                        }
                    },
                )
            },
    ) {
        ViewfinderOverlay(
            flashAvailable = true,
            flashOn = false,
            onToggleFlash = {},
            showCaptureFailure = false,
            latestPhoto = null,
            onOpenGallery = {},
            mode = mode,
            onModeChange = { mode = it },
            onShutter = { touch.onShutterClick(onShutterClick) },
            onFlip = { touch.onModeSwitch(SystemClock.uptimeMillis()) },
            modifier = Modifier.fillMaxSize(),
            onViewfinderBounds = { touch.viewfinder = it },
            onControlPlaced = { region, coords -> touch.placeControl(region, coords) },
        )
        Text(
            text = String.format(Locale.US, "%.1fx", zoom),
            style = MaterialTheme.typography.labelMedium,
            color = LenslyMuted,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
