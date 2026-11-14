package app.lensly.core.qr

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lensly.e2e.FixtureFrameSource
import app.lensly.ui.qr.QrScanner
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scanner's fixture path. Its frames go to the QR Decode Sandbox, as the camera's do, so each text is read back through
 * the service. No camera and no vault are involved.
 */
@RunWith(AndroidJUnit4::class)
class QrScannerFixtureInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theFixturePathReadsEveryTextThroughTheSandbox() {
        // QrMatrix uppercases its text unless it is told to keep the case, so the samples are uppercase.
        val texts = listOf("AIRVAULT FIXTURE 0001", "AIRVAULT FIXTURE 0002")
        val decoded = CopyOnWriteArrayList<String>()

        compose.setContent {
            val source = remember { FixtureFrameSource(texts) }
            QrScanner(onText = { decoded += it }, frameSource = source)
        }
        compose.waitUntil(timeoutMillis = 20_000) { decoded.size == texts.size }

        assertEquals(texts, decoded.toList())
    }
}
