package app.lumio.ui.send

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.scan.LumioScanScreen
import app.lumio.ui.scan.ReplyReading
import app.lumio.ui.scan.ReplyScanCard

/** What the recipient scan says before a code is read. It stays on screen while the camera looks for one. */
internal const val RECIPIENT_SCAN_HINT = "Point the camera at an address or a payment code."

/** The camera scan of a recipient: an address or a payment code. [status] says what to scan, or why the last code was refused. */
@Composable
fun RecipientScanScreen(status: String, onText: (String) -> Unit, onCancel: () -> Unit) {
    LumioScanScreen(title = "Scan Address", progress = null, statusText = status, onText = onText, onCancel = onCancel)
}

/**
 * The camera scan of Lensly's reply (F6), the fourth step of the send. [reading] says how many parts are read, and [notice] says what the last scan did,
 * such as a code that could not be used, under the words that tell the owner what to do. Back returns to the code on Show to Lensly, and Cancel Send ends
 * the send.
 */
@Composable
fun SignatureScanScreen(
    reading: ReplyReading?,
    notice: String?,
    onText: (String) -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    backLabel: String,
    onLock: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    SendPage(
        title = "Lensly Signs",
        backLabel = backLabel,
        offline = null,
        onBack = onBack,
        onLock = onLock,
        bottomAction = {
            Text(
                text = "Lensly showed something you didn't expect? Reject it there.",
                style = ClearAirType.small.copy(fontSize = 12.5.sp),
                color = palette.ink2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
            ClearAirTextAction(text = "Cancel Send", onClick = onCancel)
        },
    ) {
        SendStepper(current = STEP_SEND - 1)
        ReplyScanCard(reading = reading, onText = onText, modifier = Modifier.padding(top = 14.dp))
        Text(
            text = "Point at Lensly's Reply",
            style = ClearAirType.titleL,
            color = palette.ink,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "Take your time. Lensly shows every detail before it signs, so read it there. Nothing is sent until this reply is scanned.",
            style = ClearAirType.body,
            color = palette.ink2,
            modifier = Modifier.padding(top = 6.dp),
        )
        notice?.let {
            Text(text = it, style = ClearAirType.small, color = palette.ink, modifier = Modifier.padding(top = ClearAirSpacing.xs))
        }
    }
}
