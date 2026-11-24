package app.lumio.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lumio.ui.pair.PAIR_CAMERA_DENIED_TEXT
import app.lumio.ui.pair.PairIntro
import app.lumio.ui.scan.CameraDeniedNote
import app.lumio.ui.scan.LumioScanFrame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of the Pair intro, the empty state of Wallets, and of the pairing scan with its ring part way round. The camera is replaced by a
 * placeholder, as the other scan goldens do. They use the same theme, size and recording as [LumioScreenshotTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairScreensScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pairIntro() = compose.lumioSnapshot("pair_intro") {
        PairIntro(onScan = {})
    }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun pairIntroLight() = compose.lumioSnapshot("pair_intro_light") {
        PairIntro(onScan = {})
    }

    @Test
    fun scanPairProgress() = compose.lumioSnapshot("scan_pair_progress") {
        LumioScanFrame(
            title = "Scan Pairing Code",
            progress = 0.6f,
            statusText = "12 of about 20 parts · hold steady",
            complete = false,
            onCancel = {},
        ) { viewport ->
            androidx.compose.foundation.layout.Box(modifier = viewport.background(MaterialTheme.colorScheme.surfaceContainerLow))
        }
    }

    @Test
    fun scanPairCameraDenied() = compose.lumioSnapshot("scan_pair_camera_denied") {
        LumioScanFrame(
            title = "Scan Pairing Code",
            progress = null,
            statusText = "",
            complete = false,
            onCancel = {},
        ) { viewport ->
            CameraDeniedNote(text = PAIR_CAMERA_DENIED_TEXT, onOpenSettings = {}, modifier = viewport)
        }
    }

    /** Camera Denied on a scan that reads any code, such as the address scan of Send. */
    @Test
    fun scanCameraDenied() = compose.lumioSnapshot("scan_camera_denied") {
        LumioScanFrame(
            title = "Scan Address",
            progress = null,
            statusText = "",
            complete = false,
            onCancel = {},
        ) { viewport ->
            CameraDeniedNote(onOpenSettings = {}, modifier = viewport)
        }
    }
}
