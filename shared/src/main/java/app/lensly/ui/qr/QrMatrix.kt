package app.lensly.ui.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.max

/** Pixels for one QR code, as ARGB, row by row. [size] is both the width and the height. */
class QrPixels(val size: Int, val argb: IntArray)

/**
 * QR encoding helpers. Pure Kotlin with no Android types, so they run on the JVM under unit tests.
 */
object QrMatrix {

    /** Quiet zone in modules, as passed to ZXing. The card around the code adds more white space. */
    const val MARGIN = 1

    /**
     * Encodes [text] as a QR code with error correction [level] (L unless asked for more) and a [margin] of modules.
     *
     * The text is uppercased first, unless [keepCase] is set. Uppercase input stays in ZXing's alphanumeric mode, which
     * uses about 30% fewer bits per character than byte mode, so each animated part carries more data. [keepCase] is
     * for text that must scan back as written, such as a checksummed address, whose letter case is its checksum.
     */
    fun encode(
        text: String,
        keepCase: Boolean = false,
        level: ErrorCorrectionLevel = ErrorCorrectionLevel.L,
        margin: Int = MARGIN,
    ): BitMatrix = QRCodeWriter().encode(
        if (keepCase) text else text.uppercase(),
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to level,
            EncodeHintType.MARGIN to margin,
        ),
    )

    /**
     * The pixels of [text] as a code whose centre holds a logo: error correction H, so the logo hides a share of the modules and the code still scans.
     * [keepCase] is set, as for an address, whose letter case is its checksum. [margin] is in modules, and [targetPx] as for [toPixels].
     */
    fun pixelsForCentredLogo(text: String, margin: Int, targetPx: Int): QrPixels =
        toPixels(encode(text, keepCase = true, level = ErrorCorrectionLevel.H, margin = margin), targetPx)

    /**
     * Renders [matrix] as black modules on white. Each module is an integer square of pixels, so
     * edges stay sharp when the bitmap is drawn with nearest-neighbour filtering. The scale is the
     * largest whole number that keeps the bitmap at or below [targetPx] (and at least one pixel).
     */
    fun toPixels(matrix: BitMatrix, targetPx: Int = 384): QrPixels {
        val modules = matrix.width
        val scale = max(1, targetPx / modules)
        val size = modules * scale
        val argb = IntArray(size * size)
        for (y in 0 until size) {
            val moduleY = y / scale
            val rowStart = y * size
            for (x in 0 until size) {
                argb[rowStart + x] = if (matrix.get(x / scale, moduleY)) BLACK else WHITE
            }
        }
        return QrPixels(size, argb)
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
