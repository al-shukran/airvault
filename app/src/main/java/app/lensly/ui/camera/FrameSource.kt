package app.lensly.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Analysis frames are requested at about 720p. Smaller frames decode faster and keep latency low. */
private val ANALYSIS_SIZE = Size(1280, 720)

/**
 * Where the QR scanner gets its frames. [CameraFrameSource] is the production source: the CameraX analysis use case.
 *
 * The seam exists so a test can push generated frames through the real decoder, with no camera and no permission prompt.
 * A source emits bitmaps and does not decode them; the scanner decodes every frame the same way whichever source sent it.
 */
fun interface FrameSource {
    /**
     * Starts sending frames to [emit], which may be called from any thread, once per frame, until [FrameSourceHandle.stop]
     * is called. Call this on the main thread.
     */
    fun start(emit: (Bitmap) -> Unit): FrameSourceHandle
}

/** A started [FrameSource]. [stop] releases the camera, the thread, or whatever else the source holds. Call it on the main thread. */
fun interface FrameSourceHandle {
    fun stop()
}

/**
 * The CameraX source: an [ImageAnalysis] use case at about 720p, bound beside [previewView] to [lifecycleOwner]. Each
 * analysis frame is converted to a bitmap and handed to the scanner, which closes nothing: the source closes the image.
 */
class CameraFrameSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
) : FrameSource {

    override fun start(emit: (Bitmap) -> Unit): FrameSourceHandle {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val handle = Handle(analysisExecutor)

        providerFuture.addListener({
            // The composable can leave before the provider is ready, so do nothing in that case.
            if (handle.stopped) {
                analysisExecutor.shutdown()
                return@addListener
            }
            val provider = providerFuture.get()

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
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                try {
                    emit(image.toBitmap())
                } finally {
                    image.close()
                }
            }

            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis,
            )
            handle.attach(provider, imageAnalysis)
        }, mainExecutor)
        return handle
    }

    /** The started use cases. A [stop] that arrives before the provider is ready leaves nothing to release. */
    private class Handle(private val analysisExecutor: ExecutorService) : FrameSourceHandle {
        @Volatile
        var stopped = false
            private set

        private var provider: ProcessCameraProvider? = null
        private var analysis: ImageAnalysis? = null

        fun attach(provider: ProcessCameraProvider, analysis: ImageAnalysis) {
            this.provider = provider
            this.analysis = analysis
            if (stopped) release()
        }

        override fun stop() {
            stopped = true
            release()
            analysisExecutor.shutdown()
        }

        private fun release() {
            analysis?.clearAnalyzer()
            provider?.unbindAll()
            analysis = null
            provider = null
        }
    }
}
