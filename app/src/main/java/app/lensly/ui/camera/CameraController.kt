package app.lensly.ui.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** CameraX session behind the viewfinder: binding, focus, zoom, flash and still capture. */
class CameraController {
    private var previewView: PreviewView? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var zoomRatio = 1f

    /** True when the bound lens has a flash unit. */
    val hasFlashUnit: Boolean
        get() = camera?.cameraInfo?.hasFlashUnit() == true

    /** Whether still captures fire the flash. Has no effect on a lens without a flash unit. */
    var flashEnabled: Boolean = false
        set(value) {
            field = value
            applyFlash()
        }

    /** Binds preview and still capture to [owner]. Returns false when [selector] is not available. */
    suspend fun bind(
        context: Context,
        owner: LifecycleOwner,
        view: PreviewView,
        selector: CameraSelector,
    ): Boolean = try {
        val provider = awaitProvider(context)
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val capture = ImageCapture.Builder().build()
        camera = null
        imageCapture = null
        provider.unbindAll()
        val bound = provider.bindToLifecycle(owner, selector, preview, capture)
        camera = bound
        imageCapture = capture
        previewView = view
        zoomRatio = bound.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        applyFlash()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** Tap-to-focus at a point in preview pixels. */
    fun focusAt(x: Float, y: Float) {
        val control = camera?.cameraControl ?: return
        val point = previewView?.meteringPointFactory?.createPoint(x, y) ?: return
        control.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    /** Pinch zoom. [scale] multiplies the current zoom and is clamped to the lens range. */
    fun zoomBy(scale: Float) {
        val bound = camera ?: return
        val range = bound.cameraInfo.zoomState.value ?: return
        zoomRatio = (zoomRatio * scale).coerceIn(range.minZoomRatio, range.maxZoomRatio)
        bound.cameraControl.setZoomRatio(zoomRatio)
    }

    /**
     * Saves one JPEG to [file]. [onSaved] runs on [executor] once the file is written. [onFailure] runs on [executor]
     * when the capture fails, and the partial file is removed first.
     */
    fun takePhoto(file: File, executor: Executor, onSaved: () -> Unit, onFailure: () -> Unit = {}) {
        val capture = imageCapture ?: return
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onSaved()
                }

                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    onFailure()
                }
            },
        )
    }

    private fun applyFlash() {
        imageCapture?.flashMode =
            if (flashEnabled && hasFlashUnit) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    private suspend fun awaitProvider(context: Context): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { continuation.resumeWith(runCatching { future.get() }) },
                ContextCompat.getMainExecutor(context),
            )
            continuation.invokeOnCancellation { future.cancel(false) }
        }
}
