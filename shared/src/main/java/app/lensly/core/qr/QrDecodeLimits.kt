package app.lensly.core.qr

/**
 * Limits on what the QR Decode Sandbox takes and returns. The sandbox checks each frame before it decodes it, and the app
 * checks each reply again, so neither side trusts the other's sizes.
 */
object QrDecodeLimits {
    /** The largest luminance plane the sandbox takes, in bytes. A 1280 x 720 camera frame is under 1 MB. */
    const val MAX_FRAME_BYTES = 20 * 1024 * 1024

    /** The largest side of a frame, in pixels. */
    const val MAX_SIDE_PX = 4096

    /** The largest frame, in pixels: 4K UHD. While it decodes, the sandbox holds about 8 bytes per pixel. */
    const val MAX_PIXELS = 3840 * 2160

    /** The most texts one reply carries. */
    const val MAX_TEXTS = 4

    /** The longest text one reply carries, in characters. A longer text is dropped, not cut. */
    const val MAX_TEXT_CHARS = 8192

    /**
     * Null when a frame may be decoded, otherwise the reason it may not. [sharedBytes] is the size of the shared memory that
     * holds the plane.
     */
    fun frameProblem(width: Int, height: Int, rowStride: Int, sharedBytes: Long): String? = when {
        width <= 0 || height <= 0 -> "The frame has no pixels."
        width > MAX_SIDE_PX || height > MAX_SIDE_PX -> "The frame side is over $MAX_SIDE_PX pixels."
        width.toLong() * height > MAX_PIXELS -> "The frame is over $MAX_PIXELS pixels."
        rowStride < width -> "The row stride is shorter than the width."
        rowStride.toLong() * height > MAX_FRAME_BYTES -> "The frame is over $MAX_FRAME_BYTES bytes."
        sharedBytes < rowStride.toLong() * height -> "The shared memory is shorter than the frame."
        else -> null
    }

    /** The texts a reply may carry: the first [MAX_TEXTS] that are not empty and not longer than [MAX_TEXT_CHARS]. */
    fun acceptReply(texts: List<String>): List<String> =
        texts.filter { it.isNotEmpty() && it.length <= MAX_TEXT_CHARS }.take(MAX_TEXTS)
}
