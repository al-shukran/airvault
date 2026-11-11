package app.lensly.ui.gate

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.DotProgress
import app.lensly.ui.components.SecureKeyboard

/**
 * Unlock screen. It shows no error text, ever: a wrong password just clears the field, so the screen
 * never says which factor failed or how many tries are left.
 *
 * While [busy] (key derivation takes about a second) a dot-matrix progress row replaces the empty
 * space under the field, and the keyboard ignores input.
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
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        DotHeadline(text = "UNLOCK")

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

private val PROGRESS_SLOT_HEIGHT = 12.dp
