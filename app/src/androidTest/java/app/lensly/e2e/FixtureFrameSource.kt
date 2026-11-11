package app.lensly.e2e

import android.graphics.Bitmap
import app.lensly.ui.camera.FrameSource
import app.lensly.ui.camera.FrameSourceHandle
import app.lensly.ui.qr.QrMatrix
import kotlin.math.max

/**
 * A [FrameSource] that hands the QR scanner a fixed list of texts as images, once each, in order.
 *
 * Nothing is decoded here: the scanner's own decode path runs over every bitmap, so the test exercises the decoder the app
 * ships. The frames are queued when [start] is called, which is on the main thread, so no producer thread or sleep is
 * needed; the scanner decodes them on its own single thread in the order they were queued.
 */
internal class FixtureFrameSource(private val texts: List<String>) : FrameSource {

    override fun start(emit: (Bitmap) -> Unit): FrameSourceHandle {
        texts.forEach { text -> emit(qrBitmap(text)) }
        return FrameSourceHandle { }
    }
}

/**
 * [text] as a black-on-white QR image, rasterised with a [QUIET_ZONE]-module margin and whole-pixel modules, as a scanner
 * expects to see it. The image is about [targetPx] wide, and never coarser than a module per pixel.
 */
internal fun qrBitmap(text: String, targetPx: Int = 640): Bitmap {
    val matrix = QrMatrix.encode(text)
    val scale = max(1, targetPx / (matrix.width + 2 * QUIET_ZONE))
    val side = (matrix.width + 2 * QUIET_ZONE) * scale
    val argb = IntArray(side * side) { WHITE }
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            if (!matrix.get(x, y)) continue
            for (dy in 0 until scale) {
                for (dx in 0 until scale) {
                    val px = (x + QUIET_ZONE) * scale + dx
                    val py = (y + QUIET_ZONE) * scale + dy
                    argb[py * side + px] = BLACK
                }
            }
        }
    }
    return Bitmap.createBitmap(argb, side, side, Bitmap.Config.ARGB_8888)
}

private const val QUIET_ZONE = 4
private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
