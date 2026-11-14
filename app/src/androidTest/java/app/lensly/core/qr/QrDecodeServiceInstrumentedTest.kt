package app.lensly.core.qr

import android.content.ComponentName
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lensly.ui.qr.QrMatrix
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The QR Decode Sandbox, run for real: the service is bound in its own process and decodes real frames. */
@RunWith(AndroidJUnit4::class)
class QrDecodeServiceInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theSandboxIsAnIsolatedProcessThatOtherAppsCannotBind() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, QrDecodeService::class.java), 0)

        assertTrue((info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS) != 0)
        assertFalse(info.exported)
        assertTrue(info.processName.endsWith(":qrdecode"))
    }

    @Test
    fun aSampleQrPngDecodesThroughTheSandbox() {
        val text = "AIRVAULT SAMPLE 0001"
        val png = qrPng(text)
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)

        withClient { client ->
            assertEquals(QrDecodeOutcome.Answered(listOf(text)), client.decode(lumaOf(bitmap), bitmap.width, bitmap.height))
        }
    }

    @Test
    fun aBlankFrameIsAnsweredWithNoTexts() {
        val blank = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

        withClient { client ->
            assertEquals(QrDecodeOutcome.Answered(emptyList()), client.decode(lumaOf(blank), blank.width, blank.height))
        }
    }

    @Test
    fun aFrameOutsideTheLimitsIsRefusedBySandboxAndTheAppSeesItUnavailable() {
        // 4097 pixels across is over the side limit, so the sandbox refuses the frame before it decodes it.
        withClient { client ->
            assertEquals(QrDecodeOutcome.Unavailable, client.decode(ByteArray(4097), width = 4097, height = 1))
        }
    }

    private fun withClient(block: (QrDecodeClient) -> Unit) {
        val client = QrDecodeClient(BinderQrDecodeTransport(context))
        try {
            block(client)
        } finally {
            client.close()
        }
    }

    private fun lumaOf(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return LumaFrames.fromArgb(pixels, bitmap.width, bitmap.height)
    }

    /** A QR code of [text] as a PNG file, drawn by the app's own encoder. */
    private fun qrPng(text: String): ByteArray {
        val code = QrMatrix.toPixels(QrMatrix.encode(text, keepCase = true))
        val bitmap = Bitmap.createBitmap(code.argb, code.size, code.size, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }
}
