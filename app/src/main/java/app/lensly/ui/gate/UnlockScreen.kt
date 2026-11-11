package app.lensly.ui.gate

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.theme.LenslySpacing

/**
 * Unlock screen. It shows no error text, ever: a wrong password just clears the field, so the screen
 * never says which factor failed or how many tries are left.
 *
 * While [busy] (key derivation takes about a second) the status line shows a progress row, and the keyboard ignores
 * input. The line has a fixed height, so the keyboard does not move when the row comes and goes.
 */
@Composable
fun UnlockScreen(
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
            .padding(horizontal = LenslySpacing.md),
    ) {
        Spacer(Modifier.height(LenslySpacing.lg))
        Text(
            text = "APP PASSWORD",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        DotHeadline(text = "UNLOCK")

        Spacer(Modifier.weight(1f))

        PasswordField(length = length)
        Spacer(Modifier.height(LenslySpacing.sm))
        GateStatusLine(text = if (busy) "Unlocking" else null, working = busy)

        Spacer(Modifier.weight(1f))

        SecureKeyboard(
            onKey = onKey,
            onBackspace = onBackspace,
            onDone = onSubmit,
            enabled = !busy,
        )
    }
}
