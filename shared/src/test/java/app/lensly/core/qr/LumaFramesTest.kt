package app.lensly.core.qr

import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.junit.Test

class LumaFramesTest {

    @Test
    fun aPlaneWithRowPaddingIsCopiedWithoutThePadding() {
        // Three pixels a row, four bytes apart: each ninth byte is padding and must not reach the frame.
        val plane = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 9, 4, 5, 6, 9))

        assertContentEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6),
            LumaFrames.fromPlane(plane, width = 3, height = 2, rowStride = 4, pixelStride = 1),
        )
    }

    @Test
    fun aPlaneWhosePixelsAreTwoBytesApartTakesEveryOtherByte() {
        // In an interleaved layout another byte sits between the luminance bytes. Only the luminance is kept.
        val plane = ByteBuffer.wrap(byteArrayOf(10, 0, 20, 0, 30, 0))

        assertContentEquals(
            byteArrayOf(10, 20, 30),
            LumaFrames.fromPlane(plane, width = 3, height = 1, rowStride = 6, pixelStride = 2),
        )
    }

    @Test
    fun copyingAPlaneLeavesItsPositionAlone() {
        val plane = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))

        LumaFrames.fromPlane(plane, width = 2, height = 2, rowStride = 2, pixelStride = 1)

        assertEquals(0, plane.position())
    }

    @Test
    fun argbPixelsBecomeTheirLuminance() {
        val luma = LumaFrames.fromArgb(
            intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF00FF00.toInt()),
            width = 3,
            height = 1,
        )

        assertContentEquals(byteArrayOf(255.toByte(), 0, 149.toByte()), luma)
    }

    @Test
    fun aLumaFrameBecomesOpaqueGreyPixels() {
        val luma = ByteBuffer.wrap(byteArrayOf(0, 0x80.toByte(), (-1).toByte()))
        val pixels = IntArray(3)

        LumaFrames.toArgb(luma, width = 3, height = 1, rowStride = 3, out = pixels)

        assertContentEquals(intArrayOf(0xFF000000.toInt(), 0xFF808080.toInt(), 0xFFFFFFFF.toInt()), pixels)
    }

    @Test
    fun aLumaFrameWithRowPaddingReadsOnlyThePixelsOfEachRow() {
        // Two pixels a row, four bytes apart. The 0x7F padding bytes must not appear in the pixels.
        val luma = ByteBuffer.wrap(byteArrayOf(0, 0x7F, 0x7F, 0x7F, (-1).toByte(), 0x7F, 0x7F, 0x7F))
        val pixels = IntArray(4)

        LumaFrames.toArgb(luma, width = 2, height = 2, rowStride = 4, out = pixels)

        assertContentEquals(
            intArrayOf(0xFF000000.toInt(), 0xFF7F7F7F.toInt(), 0xFFFFFFFF.toInt(), 0xFF7F7F7F.toInt()),
            pixels,
        )
    }

    @Test
    fun aFrameOfTheWrongPixelCountIsRefused() {
        val error = runCatching { LumaFrames.fromArgb(IntArray(3), width = 2, height = 2) }.exceptionOrNull()

        assertEquals(IllegalArgumentException::class, error!!::class)
    }
}
