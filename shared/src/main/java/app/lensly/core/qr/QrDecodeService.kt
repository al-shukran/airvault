package app.lensly.core.qr

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.IBinder
import android.os.SharedMemory
import zxingcpp.BarcodeReader

/**
 * The QR Decode Sandbox. Every camera frame is decoded here, in a process of its own. The manifest declares it isolated, so
 * the native decoder runs with no permissions, no app files and no Keystore. A frame that breaks the decoder ends this
 * process and nothing else. The app then sees the sandbox as unavailable.
 */
class QrDecodeService : Service() {
    private val decoder = LumaDecoder()

    private val binder = object : IQrDecoder.Stub() {
        override fun decode(frame: SharedMemory, width: Int, height: Int, rowStride: Int): Array<String> =
            decoder.decode(frame, width, height, rowStride).toTypedArray()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

/**
 * Decodes one frame at a time with zxing-cpp, the decoder the scanner uses. zxing-cpp reads bitmaps, so the luminance is
 * drawn into an opaque ARGB bitmap first. The bitmap and the pixel array are kept between frames of the same size.
 */
private class LumaDecoder {
    private val reader = BarcodeReader(
        BarcodeReader.Options().apply {
            formats = setOf(BarcodeReader.Format.QR_CODE)
            tryHarder = false
        },
    )
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)

    /** The texts in the frame. Throws IllegalArgumentException when the frame is outside QrDecodeLimits. */
    @Synchronized
    fun decode(frame: SharedMemory, width: Int, height: Int, rowStride: Int): List<String> {
        QrDecodeLimits.frameProblem(width, height, rowStride, frame.size.toLong())?.let { throw IllegalArgumentException(it) }
        val luma = frame.mapReadOnly()
        try {
            val target = bitmapFor(width, height)
            LumaFrames.toArgb(luma, width, height, rowStride, pixels)
            target.setPixels(pixels, 0, width, 0, 0, width, height)
            val texts = reader.read(target, Rect(0, 0, width, height)).mapNotNull { result ->
                if (result.error == null) result.text else null
            }
            return QrDecodeLimits.acceptReply(texts)
        } finally {
            SharedMemory.unmap(luma)
        }
    }

    private fun bitmapFor(width: Int, height: Int): Bitmap {
        val current = bitmap
        if (current != null && current.width == width && current.height == height) return current
        current?.recycle()
        pixels = IntArray(width * height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
    }
}
