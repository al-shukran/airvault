package app.lensly.ui.flow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.DotProgress
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.gate.PasswordField

private val PROGRESS_SLOT_HEIGHT = 12.dp

/**
 * The App Password check before a signature. It is the unlock screen with a CONFIRM title and a line that says why the
 * password is asked. Like the unlock screen, it never shows an error. While [busy], the dots run and the keyboard is off.
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        DotHeadline(text = "CONFIRM")
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Enter your App Password to sign.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.weight(1f))

        PasswordField(length = length)
        Spacer(Modifier.height(12.dp))
        // Reserved height, so the keyboard does not move while the progress row comes and goes.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PROGRESS_SLOT_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) DotProgress()
        }

        Spacer(Modifier.weight(1f))

        SecureKeyboard(
            onKey = onKey,
            onBackspace = onBackspace,
            onDone = onSubmit,
            enabled = !busy,
        )
    }
}
