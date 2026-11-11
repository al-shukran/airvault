package app.lensly.ui.qr

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslySpacing

/**
 * Show screen: the [title] in the top bar, a [subtitle] line that says what to do with the code, the animated QR code at
 * full width, and a DONE pill. [parts] is one UR fragment per frame; a single part shows a still code. [subtitleLeading],
 * when set, sits before the subtitle, for example the Ethereum logo on a pairing screen. [onBack], when set, adds a back
 * arrow to the top bar.
 */
@Composable
fun ShowQrScreen(
    title: String,
    subtitle: String,
    parts: List<String>,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    subtitleLeading: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    LenslyScreen(
        title = title,
        modifier = modifier,
        onBack = onBack,
        scrollable = false,
        bottomAction = {
            PillButton(
                text = "Done",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            subtitleLeading?.let { leading ->
                leading()
                Spacer(Modifier.width(LenslySpacing.xs))
            }
            Text(
                text = subtitle,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        AnimatedQr(parts = parts, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
    }
}
