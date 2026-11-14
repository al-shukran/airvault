package app.lensly.ui.camera

import android.graphics.Bitmap

/**
 * Where the QR scanner gets frames when the camera is not the source.
 *
 * The app passes null everywhere, and then the scanner runs the camera exactly as before: the CameraX analysis use case,
 * the permission gate, and the preview. A test passes a source of generated frames instead, so it can drive the real
 * decoder with no camera and no permission prompt. This is a seam only: it adds no code to the production path.
 */
fun interface FrameSource {
    /**
     * Starts sending frames to [emit], which may be called from any thread, once per frame, until [FrameSourceHandle.stop]
     * is called.
     */
    fun start(emit: (Bitmap) -> Unit): FrameSourceHandle
}

/** A started [FrameSource]. [stop] releases whatever the source holds. */
fun interface FrameSourceHandle {
    fun stop()
}
