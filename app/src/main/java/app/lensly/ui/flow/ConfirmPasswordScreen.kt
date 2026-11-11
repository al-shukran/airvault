package app.lensly.ui.flow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.DotProgress
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.gate.PasswordField
import app.lensly.ui.theme.LenslySpacing

private val STATUS_SLOT_HEIGHT = 24.dp

/**
 * The App Password check before a signature. It is the unlock screen with a CONFIRM title and a line that says why the
 * password is asked. Like the unlock screen, it never shows an error: a wrong password only clears the field. While
 * [busy], the status row says the password is being checked, and the keyboard is off.
 */
@Composable
fun ConfirmPasswordScreen(
    length: Int,
    busy: Boolean,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(
        title = "Confirm",
        modifier = modifier,
        scrollable = false,
        bottomAction = {
            SecureKeyboard(
                onKey = onKey,
                onBackspace = onBackspace,
                onDone = onSubmit,
                enabled = !busy,
            )
        },
    ) {
        Text(
            text = "Enter your App Password to sign.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.weight(1f))

        PasswordField(length = length)
        Spacer(Modifier.height(LenslySpacing.sm))
        // Reserved height, so the keyboard does not move while the status comes and goes. It starts at the left
        // edge, under the password dots.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(STATUS_SLOT_HEIGHT),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DotProgress(description = "Checking password")
                    Spacer(Modifier.width(LenslySpacing.sm))
                    Text(
                        text = "CHECKING",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
    }
}
