package app.lumio.ui.send

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.scan.LumioScanScreen

/** What the recipient scan says before a code is read. It stays on screen while the camera looks for one. */
internal const val RECIPIENT_SCAN_HINT = "Point the camera at an address or a payment code."

/** What the reply scan says before a part of Lensly's reply is read (F6). */
internal const val REPLY_SCAN_HINT = "Point at Lensly's Reply"

/** What the reply scan says once parts are read: how many of how many (F6). */
internal fun replyProgress(received: Int, expected: Int): String = "Reading Lensly's reply · $received of $expected"

/** The camera scan of a recipient: an address or a payment code. [status] says what to scan, or why the last code was refused. */
@Composable
fun RecipientScanScreen(status: String, onText: (String) -> Unit, onCancel: () -> Unit) {
    LumioScanScreen(title = "Scan Address", progress = null, statusText = status, onText = onText, onCancel = onCancel)
}

/**
 * The camera scan of Lensly's reply (F6). [progress] shows for an animated code, and [status] says what the scan is doing. Back returns to the code
 * on Show to Lensly, and Cancel Send ends the send.
 */
@Composable
fun SignatureScanScreen(progress: Float?, status: String, onText: (String) -> Unit, onBack: () -> Unit, onCancel: () -> Unit) {
    val palette = LocalClearAirPalette.current
    LumioScanScreen(
        title = "Lensly Signs",
        progress = progress,
        statusText = status,
        onText = onText,
        onCancel = onBack,
        footer = {
            Text(
                text = "Lensly showed something you didn't expect? Reject it there.",
                style = ClearAirType.body,
                color = palette.ink2,
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ClearAirTextAction(text = "Cancel Send", onClick = onCancel)
            }
        },
    )
}
