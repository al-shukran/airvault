package app.lensly.ui.gate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.theme.LenslyPillShape
import app.lensly.ui.theme.LenslySpacing

/** Minimum password length, shown to the user as "8+ characters". */
const val MIN_PASSWORD_LENGTH = 8

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

internal fun PolicyError.message(): String = when (this) {
    PolicyError.TooShort -> "NEEDS $MIN_PASSWORD_LENGTH+ CHARACTERS"
    PolicyError.TooWeak -> "TOO EASY TO GUESS. ADD VARIETY."
    PolicyError.Mismatch -> "DOES NOT MATCH. TRY AGAIN."
}

/**
 * Two-step password creation: enter, then confirm. Keys go through the in-app [SecureKeyboard], so
 * the system IME never sees the password.
 *
 * [onSubmit] is the keyboard's done key. The caller checks the policy for the current step and
 * updates [state]; the screen does no checking itself. While entering the first password, a hint counts down the
 * characters still needed. A rejection shows in the error colour on the status line. While [busy] (the vault is being
 * created) the keyboard is disabled and a progress row shows on the status line.
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
    val error = state.error
    val hint = if (!confirming && error == null && state.length in 1 until MIN_PASSWORD_LENGTH) {
        "${MIN_PASSWORD_LENGTH - state.length} more to reach $MIN_PASSWORD_LENGTH"
    } else {
        null
    }
    val status: String? = when {
        busy -> "Creating vault"
        error != null -> error.message()
        else -> hint
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = LenslySpacing.md),
    ) {
        Spacer(Modifier.height(LenslySpacing.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (confirming) "STEP 2 OF 2" else "STEP 1 OF 2",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(LenslySpacing.sm))
            StepBar(active = true)
            Spacer(Modifier.width(LenslySpacing.xxs))
            StepBar(active = confirming)
        }
        Spacer(Modifier.height(LenslySpacing.sm))
        // A fixed height keeps the policy chip in the same place on both steps.
        Box(modifier = Modifier.fillMaxWidth().height(HEADLINE_HEIGHT)) {
            DotHeadline(text = if (confirming) "CONFIRM" else "SET PASSWORD")
        }
        Spacer(Modifier.height(LenslySpacing.md))
        PillChip(
            text = if (confirming) "Re-enter to confirm" else "$MIN_PASSWORD_LENGTH+ characters, hard to guess",
        )

        Spacer(Modifier.weight(1f))

        PasswordField(length = state.length)
        Spacer(Modifier.height(LenslySpacing.sm))
        GateStatusLine(
            text = status,
            working = busy,
            tone = if (error != null && !busy) colors.error else colors.onSurfaceVariant,
        )

        Spacer(Modifier.weight(1f))

        SecureKeyboard(
            onKey = onKey,
            onBackspace = onBackspace,
            onDone = onSubmit,
            enabled = !busy,
        )
    }
}

/** One segment of the step indicator. Lit when its step has been reached. */
@Composable
private fun StepBar(active: Boolean) {
    Box(
        modifier = Modifier
            .width(STEP_BAR_WIDTH)
            .height(STEP_BAR_HEIGHT)
            .clip(LenslyPillShape)
            .background(if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline),
    )
}

private val HEADLINE_HEIGHT = 136.dp
private val STEP_BAR_WIDTH = 24.dp
private val STEP_BAR_HEIGHT = 4.dp
