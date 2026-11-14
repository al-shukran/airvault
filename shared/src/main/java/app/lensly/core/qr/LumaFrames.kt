package app.lensly.core.qr

import java.nio.ByteBuffer

/**
 * Luminance frames as the QR Decode Sandbox takes them: one byte per pixel, row after row, with no padding. A camera's Y
 * plane and a bitmap both come to this form, so the sandbox has one input.
 */
object LumaFrames {
    /**
     * Copies the luminance plane of a camera frame into [width] x [height] bytes. Its rows are [rowStride] bytes apart and
     * its pixels are [pixelStride] bytes apart. The plane's buffer keeps its position.
     */
    fun fromPlane(plane: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteArray {
        val source = plane.duplicate()
        val out = ByteArray(width * height)
        for (row in 0 until height) {
            val start = row * rowStride
            if (pixelStride == 1) {
                source.position(start)
                source.get(out, row * width, width)
            } else {
                for (x in 0 until width) out[row * width + x] = source.get(start + x * pixelStride)
            }
        }
        return out
    }

    /** The luminance of ARGB [pixels], one byte per pixel, with the Rec. 601 weights in integer form. */
    fun fromArgb(pixels: IntArray, width: Int, height: Int): ByteArray {
        require(pixels.size == width * height) { "The pixel count does not match the size." }
        return ByteArray(pixels.size) { index ->
            val pixel = pixels[index]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            ((77 * red + 150 * green + 29 * blue) shr 8).toByte()
        }
    }

    /**
     * Writes the opaque grey ARGB pixels of a luminance frame into [out], which holds [width] x [height] ints. Only the first
     * [width] bytes of each [rowStride] are read.
     */
    fun toArgb(luma: ByteBuffer, width: Int, height: Int, rowStride: Int, out: IntArray) {
        for (row in 0 until height) {
            val start = row * rowStride
            for (x in 0 until width) {
                val value = luma.get(start + x).toInt() and 0xFF
                out[row * width + x] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
            }
        }
    }
}
