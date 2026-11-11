package app.lensly.benchmark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lensly.core.ur.EthSignatureEncoder
import app.lensly.core.ur.UrScanner
import app.lensly.ui.qr.QrMatrix
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import zxingcpp.BarcodeReader

/**
 * QR decode rate over fixture frames, in frames per second (guideline 1.7).
 *
 * One benchmark iteration is one camera-sized frame: the same zxing-cpp reader and options that `QrScanner` builds,
 * then the app's own [UrScanner] to accept the text. The frames are real UR parts from [EthSignatureEncoder], drawn
 * as a QR code on a 1280x720 canvas, which is the analysis size the scanner asks CameraX for.
 *
 * The reader decodes a [Bitmap] here instead of a CameraX `ImageProxy`; zxing-cpp converts both to the same native
 * input, so the decode work is the same.
 *
 * Run with `./gradlew :app:connectedBenchmarkAndroidTest`. The plain AndroidJUnitRunner that debug tests use cannot
 * run this class, so the instrumented lane excludes `app.lensly.benchmark` (scripts/ci/run-extended.sh).
 */
@RunWith(AndroidJUnit4::class)
class QrDecodeBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val reader = BarcodeReader(
        BarcodeReader.Options().apply {
            formats = setOf(BarcodeReader.Format.QR_CODE)
            tryHarder = false
        },
    )

    /** Distinct UR parts, built once: QR encoding is setup, not the work being measured. */
    private val frames: List<Bitmap> = fixtureFrames()

    @Test
    fun decodeOneFrame() {
        val scanner = UrScanner()
        var index = 0
        // Written to on every iteration and read after the loop, so the decoder call cannot be optimised away.
        var decoded = 0

        benchmarkRule.measureRepeated {
            val frame = frames[index % frames.size]
            index++
            val text = reader.read(frame).firstNotNullOfOrNull { result ->
                result.text?.takeIf { it.isNotEmpty() && result.error == null }
            }
            if (text != null && scanner.receive(text)) decoded++
        }

        check(decoded > 0) { "no fixture frame decoded" }
    }

    private fun fixtureFrames(): List<Bitmap> {
        val parts = EthSignatureEncoder.encode(
            requestId = ByteArray(16) { (it * 3).toByte() },
            signature = ByteArray(65) { (it * 7).toByte() },
            // Small fragments give an animated code: several different frames instead of one repeated.
            maxFragment = 40,
        ).take(FRAME_COUNT).toList()

        return parts.map { part ->
            val pixels = QrMatrix.toPixels(QrMatrix.encode(part), targetPx = QR_TARGET_PX)
            val code = Bitmap.createBitmap(pixels.argb, pixels.size, pixels.size, Bitmap.Config.ARGB_8888)
            cameraFrame(code)
        }
    }

    /** Centres [code] on a camera-sized white frame, the way the scanner sees a phone screen through a camera. */
    private fun cameraFrame(code: Bitmap): Bitmap {
        val frame = Bitmap.createBitmap(FRAME_WIDTH, FRAME_HEIGHT, Bitmap.Config.ARGB_8888)
        frame.eraseColor(Color.WHITE)
        val canvas = Canvas(frame)
        val left = (FRAME_WIDTH - code.width) / 2f
        val top = (FRAME_HEIGHT - code.height) / 2f
        canvas.drawBitmap(code, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        return frame
    }

    private companion object {
        const val FRAME_COUNT = 4
        const val QR_TARGET_PX = 512
        const val FRAME_WIDTH = 1280
        const val FRAME_HEIGHT = 720
    }
}
