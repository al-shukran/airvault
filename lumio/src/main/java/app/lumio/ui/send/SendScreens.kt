package app.lumio.ui.send

import androidx.compose.runtime.Composable
import app.lumio.ui.scan.LumioScanScreen

/** What the recipient scan says before a code is read. It stays on screen while the camera looks for one. */
internal const val RECIPIENT_SCAN_HINT = "Point the camera at an address or a payment code."

/** The camera scan of a recipient: an address or a payment code. [status] says what to scan, or why the last code was refused. */
@Composable
fun RecipientScanScreen(status: String, onText: (String) -> Unit, onCancel: () -> Unit) {
    LumioScanScreen(title = "Scan Address", progress = null, statusText = status, onText = onText, onCancel = onCancel)
}

/** The camera scan of Lensly's signature. [progress] shows for an animated code, and [status] says what the scan is doing. */
@Composable
fun SignatureScanScreen(progress: Float?, status: String, onText: (String) -> Unit, onCancel: () -> Unit) {
    LumioScanScreen(
        title = "Scan Signature",
        progress = progress,
        statusText = status,
        onText = onText,
        onCancel = onCancel,
    )
}
