package app.lensly.ui.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Test
import kotlin.test.assertEquals

class QrMatrixTest {

    @Test
    fun shortTextFitsVersionOneWithOneModuleMargin() {
        val matrix = QrMatrix.encode("hello")
        // Version 1 is 21 modules square. The one-module margin adds a module on each side.
        assertEquals(23, matrix.width)
        assertEquals(23, matrix.height)
    }

    @Test
    fun encodeUppercasesTheText() {
        val lower = QrMatrix.encode("ur:crypto-account/1-1/abc")
        val upper = QrMatrix.encode("UR:CRYPTO-ACCOUNT/1-1/ABC")
        assertEquals(upper.toString(), lower.toString())
    }

    @Test
    fun zxingCoreDecodesTheMatrixBackToUppercaseText() {
        val text = "UR:CRYPTO-PSBT/1-3/" + "0123456789ABCDEF".repeat(5)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(luminance(QrMatrix.encode(text)))))
        assertEquals(text, decoded.text)
    }

    @Test
    fun toPixelsDrawsWholeSquareModules() {
        val pixels = QrMatrix.toPixels(QrMatrix.encode("hello"), targetPx = 368)
        // 368 / 23 modules gives 16 pixels per module, so the bitmap is 368 pixels square.
        assertEquals(368, pixels.size)
        assertEquals(368 * 368, pixels.argb.size)
        // Module (0, 0) is the margin, so it is white.
        assertEquals(WHITE, pixels.argb[0])
        // Module (1, 1) is the corner of the top-left finder pattern, which is always black.
        assertEquals(BLACK, pixels.argb[(1 * 16 + 8) * 368 + (1 * 16 + 8)])
    }

    /** Puts a four-module white quiet zone around the matrix, as a scanner expects, then rasterises it. */
    private fun luminance(matrix: BitMatrix, scale: Int = 4, quietZone: Int = 4): RGBLuminanceSource {
        val side = (matrix.width + 2 * quietZone) * scale
        val pixels = IntArray(side * side) { WHITE }
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (!matrix.get(x, y)) continue
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        val px = (x + quietZone) * scale + dx
                        val py = (y + quietZone) * scale + dy
                        pixels[py * side + px] = BLACK
                    }
                }
            }
        }
        return RGBLuminanceSource(side, side, pixels)
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
