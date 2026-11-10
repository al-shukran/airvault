package app.lensly.ui.qr

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.common.BitMatrix
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import zxingcpp.BarcodeReader

/**
 * Round trip on a device: ZXing core encodes, and zxing-cpp (the native decoder the scanner uses)
 * decodes. The JVM unit tests cannot load the native library, so this runs as an instrumented test.
 */
@RunWith(AndroidJUnit4::class)
class QrDecodeInstrumentedTest {

    @Test
    fun zxingCppDecodesAZxingCoreCode() {
        val text = "UR:CRYPTO-PSBT/1-3/" + "0123456789ABCDEF".repeat(20)
        val bitmap = rasterise(QrMatrix.encode(text), scale = 8)

        assertDecodes(text, bitmap)
    }

    @Test
    fun zxingCppDecodesALongUrPartAtScannerSize() {
        // 339 characters is near the largest part the animation uses. zxing-cpp reads it correctly.
        // ZXing core's own reader does not, which is why the JVM test above uses a shorter payload.
        val text = "UR:CRYPTO-PSBT/1-3/" + "0123456789ABCDEF".repeat(20)
        val bitmap = rasterise(QrMatrix.encode(text), scale = 8)

        assertDecodes(text, bitmap)
    }

    @Test
    fun zxingCppDecodesTheProductionRasterWithCardPadding() {
        val text = "UR:CRYPTO-ACCOUNT/2-5/" + "ABCDEF0123456789".repeat(12)
        val pixels = QrMatrix.toPixels(QrMatrix.encode(text), targetPx = 384)
        val raw = Bitmap.createBitmap(pixels.argb, pixels.size, pixels.size, Bitmap.Config.ARGB_8888)
        // The card in AnimatedQr adds white padding. Mirror that here so the quiet zone is realistic.
        val padded = withWhiteBorder(raw, raw.width / 4)

        assertDecodes(text, padded)
    }

    private fun assertDecodes(expected: String, bitmap: Bitmap) {
        val reader = BarcodeReader(
            BarcodeReader.Options().apply { formats = setOf(BarcodeReader.Format.QR_CODE) },
        )
        val results = reader.read(bitmap, Rect(0, 0, bitmap.width, bitmap.height))
        assertEquals(1, results.size)
        assertEquals(expected, results.single().text)
    }

    private fun rasterise(matrix: BitMatrix, scale: Int, quietZone: Int = 4): Bitmap {
        val side = (matrix.width + 2 * quietZone) * scale
        val argb = IntArray(side * side) { WHITE }
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (!matrix.get(x, y)) continue
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        val px = (x + quietZone) * scale + dx
                        val py = (y + quietZone) * scale + dy
                        argb[py * side + px] = BLACK
                    }
                }
            }
        }
        return Bitmap.createBitmap(argb, side, side, Bitmap.Config.ARGB_8888)
    }

    private fun withWhiteBorder(source: Bitmap, border: Int): Bitmap {
        val out = Bitmap.createBitmap(
            source.width + 2 * border,
            source.height + 2 * border,
            Bitmap.Config.ARGB_8888,
        )
        out.eraseColor(WHITE)
        val canvas = android.graphics.Canvas(out)
        canvas.drawBitmap(source, border.toFloat(), border.toFloat(), null)
        return out
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
