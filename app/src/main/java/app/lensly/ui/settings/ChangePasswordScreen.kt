package app.lensly.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import app.lensly.ui.gate.DotProgress
import app.lensly.ui.gate.MIN_PASSWORD_LENGTH
import app.lensly.ui.gate.PasswordField
import app.lensly.ui.gate.PolicyError
import app.lensly.ui.gate.message
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillSize
import app.lensly.ui.wallet.WalletPillStyle

/** The three entries of a change: the current password, then the new one, then the new one again. */
enum class ChangeStep { Current, New, Confirm }

/**
 * What the change-password screen shows. The screen never sees a password, only its length. [error] is set only on
 * the new-password steps, where the owner can be told why a password was refused. [busy] covers a key derivation,
 * which takes about a second. [changed] is set once the vault accepts the new password.
 */
data class ChangePasswordState(
    val step: ChangeStep = ChangeStep.Current,
    val length: Int = 0,
    val error: PolicyError? = null,
    val busy: Boolean = false,
    val changed: Boolean = false,
)

/**
 * Change the App Password, in three steps. The current password is checked through the gate's unlock, so the
 * Failure Delay applies, and a wrong one clears the field and shows nothing, as the gate does. The new password is
 * entered twice on the in-app keyboard. [onSubmit] is the keyboard's done key, and the caller checks the policy for
 * each step.
 *
 * While [ChangePasswordState.busy] the keyboard is off and a progress row shows. [onBack] is off while busy too, so the
 * owner waits for the change to finish. [onLock] always works.
 */
@Composable
fun ChangePasswordScreen(
    state: ChangePasswordState,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.changed) {
        ChangedContent(onDone = onDone, onLock = onLock, modifier = modifier)
        return
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
    ) {
        HeaderRow(onBack = onBack, onLock = onLock, backEnabled = !state.busy)
        Spacer(Modifier.height(20.dp))
        WalletEyebrow("Step ${state.step.ordinal + 1} of 3")
        Spacer(Modifier.height(8.dp))
        // A fixed height keeps the hint chip in the same place on every step.
        Box(modifier = Modifier.fillMaxWidth().height(HEADLINE_HEIGHT)) {
            DotHeadline(text = headline(state.step))
        }
        PillChip(text = hint(state.step))

        Spacer(Modifier.weight(1f))

        PasswordField(length = state.length)
        Spacer(Modifier.height(12.dp))
        // Reserved height, so the keyboard does not move when an error or the progress row comes and goes.
        Box(
            modifier = Modifier.fillMaxWidth().height(ERROR_HEIGHT),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (state.busy) {
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
            enabled = !state.busy,
        )
    }
}

@Composable
private fun HeaderRow(onBack: (() -> Unit)?, onLock: () -> Unit, backEnabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) {
            WalletPill(
                text = "Back",
                onClick = onBack,
                style = WalletPillStyle.Outline,
                size = WalletPillSize.Compact,
                enabled = backEnabled,
            )
        }
        Spacer(Modifier.weight(1f))
        WalletPill(
            text = "Lock",
            onClick = onLock,
            style = WalletPillStyle.Outline,
            size = WalletPillSize.Compact,
        )
    }
}

@Composable
private fun ChangedContent(onDone: () -> Unit, onLock: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
    ) {
        HeaderRow(onBack = null, onLock = onLock, backEnabled = false)
        Spacer(Modifier.height(20.dp))
        WalletEyebrow("Done")
        Spacer(Modifier.height(8.dp))
        DotHeadline(text = "CHANGED")
        Spacer(Modifier.height(16.dp))
        Text(
            text = "The new password opens the vault now. The old one does not.",
            style = MaterialTheme.typography.bodyLarge,
            color = LenslyOnSurface,
        )
        Spacer(Modifier.weight(1f))
        WalletPill(
            text = "Done",
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            style = WalletPillStyle.Primary,
        )
    }
}

private fun headline(step: ChangeStep): String = when (step) {
    ChangeStep.Current -> "CURRENT"
    ChangeStep.New -> "NEW PASSWORD"
    ChangeStep.Confirm -> "CONFIRM"
}

private fun hint(step: ChangeStep): String = when (step) {
    ChangeStep.Current -> "Your password now"
    ChangeStep.New -> "$MIN_PASSWORD_LENGTH+ characters, hard to guess"
    ChangeStep.Confirm -> "Type the new password again"
}

private val HEADLINE_HEIGHT = 136.dp
private val ERROR_HEIGHT = 36.dp
