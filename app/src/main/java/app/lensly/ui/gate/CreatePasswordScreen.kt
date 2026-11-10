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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.theme.LenslyAccent

/** Minimum password length, shown to the user as "12+ characters". */
const val MIN_PASSWORD_LENGTH = 12

/** The two entry steps of password creation. */
enum class CreateStep { Enter, Confirm }

/** Why the current entry was rejected. Null means nothing to report. */
enum class PolicyError { TooShort, TooWeak, Mismatch }

/**
 * UI state for [CreatePasswordScreen]. The screen never sees the password itself, only its length.
 * The caller owns the buffer and advances [step] after it checks the policy.
 */
data class CreatePasswordState(
    val step: CreateStep = CreateStep.Enter,
    val length: Int = 0,
    val error: PolicyError? = null,
)

private fun PolicyError.message(): String = when (this) {
    PolicyError.TooShort -> "NEEDS $MIN_PASSWORD_LENGTH+ CHARACTERS"
    PolicyError.TooWeak -> "TOO EASY TO GUESS. ADD VARIETY."
    PolicyError.Mismatch -> "DOES NOT MATCH. TRY AGAIN."
}

/**
 * Two-step password creation: enter, then confirm. Keys go through the in-app [SecureKeyboard], so
 * the system IME never sees the password.
 *
 * [onSubmit] is the keyboard's done key. The caller checks the policy for the current step and
 * updates [state]; the screen does no checking itself. While [busy] (the vault is being created) the
 * keyboard is disabled and a progress row shows in the error slot.
 */
@Composable
fun CreatePasswordScreen(
    state: CreatePasswordState,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    val confirming = state.step == CreateStep.Confirm
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = if (confirming) "STEP 2 OF 2" else "STEP 1 OF 2",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        // A fixed height keeps the policy chip in the same place on both steps.
        Box(modifier = Modifier.fillMaxWidth().height(HEADLINE_HEIGHT)) {
            DotHeadline(text = if (confirming) "CONFIRM" else "SET PASSWORD")
        }
        Spacer(Modifier.height(16.dp))
        PillChip(text = "$MIN_PASSWORD_LENGTH+ CHARACTERS, HARD TO GUESS")

        Spacer(Modifier.weight(1f))

        PasswordField(length = state.length)
        Spacer(Modifier.height(12.dp))
        // Reserved height, so the keyboard does not move when an error appears or clears.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ERROR_HEIGHT),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (busy) {
                DotProgress(modifier = Modifier.padding(top = 15.dp))
            } else {
                state.error?.let { error ->
                    Text(
                        text = error.message(),
                        style = MaterialTheme.typography.labelMedium,
                        color = LenslyAccent,
                        textAlign = TextAlign.Center,
                    )
                }
            }
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

private val HEADLINE_HEIGHT = 136.dp
private val ERROR_HEIGHT = 36.dp
