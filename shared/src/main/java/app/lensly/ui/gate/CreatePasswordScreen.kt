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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.lensly.core.vault.PasswordPolicy
import app.lensly.ui.components.BottomSlot
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.KeyboardHost
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.theme.LenslyPillShape
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.labelCase

/** Minimum password length, shown to the user as "N+ characters". It is the policy's minimum, so the copy cannot drift from it. */
const val MIN_PASSWORD_LENGTH = PasswordPolicy.MIN_CODE_POINTS

/** The two entry steps of password creation. */
enum class CreateStep { Enter, Confirm }

/** Why the current entry was rejected. Null means nothing to report. */
enum class PolicyError { TooShort, TooWeak, Mismatch, SameAsApp }

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
    PolicyError.TooShort -> "Needs $MIN_PASSWORD_LENGTH+ characters"
    PolicyError.TooWeak -> "Too easy to guess. Add variety."
    PolicyError.Mismatch -> "Does not match. Try again."
    PolicyError.SameAsApp -> "THAT IS THE APPLICATION PASSWORD. CHOOSE ANOTHER."
}

/**
 * Two-step password creation: enter, then confirm. Keys go through the in-app [SecureKeyboard], so
 * the system IME never sees the password.
 *
 * [onSubmit] is the keyboard's done key. The caller checks the policy for the current step and
 * updates [state]; the screen does no checking itself. While entering the first password, a hint counts down the
 * characters still needed. A rejection shows in the error colour on the status line. While [busy] (the vault is being
 * created) the keyboard is disabled and a progress row shows on the status line. Each step opens with the keyboard up. A tap
 * outside the field or the system Back hides it, and a tap on the field brings it back.
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
        busy -> "Creating Vault"
        error != null -> error.message()
        else -> hint
    }
    var focused by rememberSaveable(state.step) { mutableStateOf(true) }

    KeyboardHost(focused = focused, onUnfocus = { focused = false }) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = LenslySpacing.md),
        ) {
            Spacer(Modifier.height(LenslySpacing.lg))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = labelCase(if (confirming) "Step 2 of 2" else "Step 1 of 2"),
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
                DotHeadline(text = if (confirming) "Confirm" else "Set Password")
            }
            Spacer(Modifier.height(LenslySpacing.md))
            PillChip(
                text = if (confirming) "Re-enter to confirm" else "$MIN_PASSWORD_LENGTH+ characters, hard to guess",
            )
            if (!confirming) {
                Spacer(Modifier.height(LenslySpacing.md))
                Text(
                    text = "Removing the screen lock makes the vault unreadable. The vault must then be restored from its backup.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }

            Spacer(Modifier.weight(1f))

            PasswordField(length = state.length, onClick = { focused = true })
            Spacer(Modifier.height(LenslySpacing.sm))
            GateStatusLine(
                text = status,
                working = busy,
                tone = if (error != null && !busy) colors.error else colors.onSurfaceVariant,
            )

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

/** One segment of the step indicator. Lit when its step has been reached. */
@Composable
private fun StepBar(active: Boolean) {
    Box(
        modifier = Modifier
            .width(STEP_BAR_WIDTH)
            .height(STEP_BAR_HEIGHT)
            .clip(LenslyPillShape)
            .background(if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant),
    )
}

private val HEADLINE_HEIGHT = 136.dp
private val STEP_BAR_WIDTH = 24.dp
private val STEP_BAR_HEIGHT = 4.dp
