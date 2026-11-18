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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.lensly.ui.components.BottomSlot
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.KeyboardHost
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.labelCase

/**
 * The notice shown as the last tries come: "2 tries left before this vault is erased". Sentence case, and no full stop, as the
 * gate's other notices have none.
 */
fun eraseWarning(triesLeft: Int): String =
    (if (triesLeft == 1) "1 try left" else "$triesLeft tries left") + " before this vault is erased"

/**
 * Unlock screen. It shows no error text, ever: a wrong password just clears the field, so the screen
 * never says which factor failed. Near the erase, [warning] says how many tries are left (see [eraseWarning]).
 *
 * The keyboard is up when the screen opens, since the password is the only task. A tap outside the field or the system
 * Back hides it, and a tap on the field brings it back.
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
    warning: String? = null,
) {
    var focused by rememberSaveable { mutableStateOf(true) }
    KeyboardHost(focused = focused, onUnfocus = { focused = false }) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = LenslySpacing.md),
        ) {
            Spacer(Modifier.height(LenslySpacing.lg))
            Text(
                text = labelCase("Application password"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(LenslySpacing.sm))
            DotHeadline(text = "Unlock")

            Spacer(Modifier.weight(1f))

            PasswordField(length = length, onClick = { focused = true })
            Spacer(Modifier.height(LenslySpacing.sm))
            GateStatusLine(text = if (busy) "Unlocking" else warning, working = busy)

            Spacer(Modifier.weight(1f))

            BottomSlot(visible = focused) {
                SecureKeyboard(
                    onKey = onKey,
                    onBackspace = onBackspace,
                    onDone = onSubmit,
                    enabled = !busy,
                )
            }
        }
    }
}
