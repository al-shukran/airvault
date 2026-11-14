package app.lensly.ui.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** CameraX session behind the viewfinder: binding, focus, zoom, flash, still capture and video recording. */
class CameraController {
    private var previewView: PreviewView? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var zoomRatio = 1f

    /** True when the bound lens also records video. A lens that cannot run video with still capture is bound for photos only. */
    var videoAvailable: Boolean = false
        private set

    /** True when the bound lens has a flash unit. */
    val hasFlashUnit: Boolean
        get() = camera?.cameraInfo?.hasFlashUnit() == true

    /** Whether still captures fire the flash. Has no effect on a lens without a flash unit. */
    var flashEnabled: Boolean = false
        set(value) {
            field = value
            applyFlash()
        }

    /** The zoom ratio the lens is at now, as pinch zoom has set it. */
    val currentZoomRatio: Float
        get() = zoomRatio

    /** Binds preview, still capture and video to [owner]. Returns false when [selector] is not available. */
    suspend fun bind(
        context: Context,
        owner: LifecycleOwner,
        view: PreviewView,
        selector: CameraSelector,
    ): Boolean = try {
        val provider = awaitProvider(context)
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val capture = ImageCapture.Builder().build()
        val video = VideoCapture.withOutput(
            Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build(),
        )
        camera = null
        imageCapture = null
        videoCapture = null
        provider.unbindAll()
        val bound = try {
            provider.bindToLifecycle(owner, selector, preview, capture, video).also {
                videoCapture = video
                videoAvailable = true
            }
        } catch (e: IllegalArgumentException) {
            // This lens cannot record video alongside still capture. Photos still work, so bind without video.
            videoAvailable = false
            provider.bindToLifecycle(owner, selector, preview, capture)
        }
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

    /**
     * Starts recording an MP4 to [file], with sound when [context] holds the microphone permission. Returns false when no
     * recording can start. Otherwise [onFinished] runs on [executor] once the file is closed, with false when the recording
     * failed; a failed file is removed first. A source that goes away mid-recording still keeps what was recorded.
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is declared by the hosting app, not by this library; the guard is below.
    fun startRecording(
        context: Context,
        file: File,
        executor: Executor,
        onFinished: (saved: Boolean) -> Unit,
    ): Boolean {
        val output = videoCapture?.output ?: return false
        val prepared = output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        // Sound only with the microphone allowed. The check sits right at the call, where lint can see the guard.
        val pending = if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            prepared.withAudioEnabled()
        } else {
            prepared
        }
        recording = pending.start(executor) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                onFinished(recordingFileSaved(event.hasError(), event.error, file))
            }
        }
        return true
    }

    /** Ends the recording in progress. Its file is saved, and the callback given to [startRecording] runs once it is closed. */
    fun stopRecording() {
        recording?.stop()
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

/**
 * Tells whether a closed recording leaves a file worth keeping. A recording the camera stopped because its source went
 * inactive still keeps what it wrote, but only when it wrote something: a missing or empty file is removed and reported
 * as failed, like any other error.
 */
internal fun recordingFileSaved(hasError: Boolean, error: Int, file: File): Boolean {
    val keptShortRecording = error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE &&
        file.isFile && file.length() > 0
    val saved = !hasError || keptShortRecording
    if (!saved) file.delete()
    return saved
}
