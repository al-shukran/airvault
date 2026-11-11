package app.lensly.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.AddressIcon
import app.lensly.ui.qr.ScanFrame
import app.lensly.ui.qr.ScanPermissionPrompt
import app.lensly.ui.qr.ShowQrScreen
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyMuted
import app.lensly.ui.theme.LenslySurfaceContainer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * QR group states. The live scanner needs the camera, so the scan frame gets a flat placeholder in its viewport. The
 * permission prompt and the status line are the real components.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun scanWaiting() = compose.snapshot("scan_waiting") {
        ScanFrame(
            title = "Sign request",
            progress = null,
            statusText = "Scan the sign request from your online wallet.",
            onCancel = {},
            viewport = { modifier -> CameraPlaceholder(modifier) },
        )
    }

    @Test
    fun scanProgress() = compose.snapshot("scan_progress") {
        ScanFrame(
            title = "Sign request",
            progress = 0.4f,
            statusText = "Reading the request. Keep the code in view.",
            onCancel = {},
            viewport = { modifier -> CameraPlaceholder(modifier) },
        )
    }

    @Test
    fun scanNotPartOfRequest() = compose.snapshot("scan_not_part_of_request") {
        ScanFrame(
            title = "Sign request",
            progress = null,
            statusText = "That code is not part of this request.",
            onCancel = {},
            viewport = { modifier -> CameraPlaceholder(modifier) },
        )
    }

    @Test
    fun scanPermissionNeeded() = compose.snapshot("scan_permission_needed") {
        ScanFrame(
            title = "Sign request",
            progress = null,
            statusText = "Scan the sign request from your online wallet.",
            onCancel = {},
            viewport = { modifier ->
                Box(modifier.background(LenslyBlack), contentAlignment = Alignment.Center) {
                    ScanPermissionPrompt(refused = false, onAllow = {}, onOpenSettings = {})
                }
            },
        )
    }

    @Test
    fun scanPermissionRefused() = compose.snapshot("scan_permission_refused") {
        ScanFrame(
            title = "Sign request",
            progress = null,
            statusText = "Scan the sign request from your online wallet.",
            onCancel = {},
            viewport = { modifier ->
                Box(modifier.background(LenslyBlack), contentAlignment = Alignment.Center) {
                    ScanPermissionPrompt(refused = true, onAllow = {}, onOpenSettings = {})
                }
            },
        )
    }

    @Test
    fun showSingleCode() = compose.snapshot("show_qr_single") {
        ShowQrScreen(
            title = "Pair wallet",
            subtitle = "In Rabby: Add address → Hardware wallet → AirGap Vault → scan",
            parts = listOf(SAMPLE_UR),
            onDone = {},
            subtitleLeading = { AddressIcon(size = 16.dp) },
            onBack = {},
        )
    }

    @Test
    fun showAnimatedCode() = compose.snapshot("show_qr_animated") {
        ShowQrScreen(
            title = "Signature",
            subtitle = "Scan this with your online wallet",
            parts = sampleUrParts(12),
            onDone = {},
        )
    }

    /** Stands in for the camera preview. It is flat, with a label, so the frame around it is what gets checked. */
    @Composable
    private fun CameraPlaceholder(modifier: Modifier) {
        Box(
            modifier = modifier.background(LenslySurfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "CAMERA PREVIEW",
                style = MaterialTheme.typography.labelMedium,
                color = LenslyMuted,
            )
        }
    }
}
