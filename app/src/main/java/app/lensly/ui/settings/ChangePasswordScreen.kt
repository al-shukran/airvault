package app.lensly.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.BottomSlot
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.KeyboardHost
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.LoadingState
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.gate.MIN_PASSWORD_LENGTH
import app.lensly.ui.gate.PasswordField
import app.lensly.ui.gate.PolicyError
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySuccess
import app.lensly.ui.wallet.WalletEyebrow

/** The three entries of a change: the current password, then the new one, then the new one again. */
enum class ChangeStep { Current, New, Confirm }

/**
 * The words of the change screen. The App Password change and the Duress Password set share the screen, and differ only here.
 * The current step always asks for the password that opened this vault, so its words are the same.
 */
data class ChangeCopy(
    val title: String,
    val newHeadline: String,
    val newHint: String,
    val changedText: String,
) {
    companion object {
        val APP_PASSWORD = ChangeCopy(
            title = "App Password",
            newHeadline = "NEW PASSWORD",
            newHint = "$MIN_PASSWORD_LENGTH+ characters, hard to guess",
            changedText = "The new password opens the vault now. The old one does not.",
        )

        val DURESS_PASSWORD = ChangeCopy(
            title = "Duress Password",
            newHeadline = "NEW PASSWORD",
            newHint = "$MIN_PASSWORD_LENGTH+ characters, not your App Password",
            changedText = "The Duress Password is set. The old one does not open anything.",
        )
    }
}

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
 * While [ChangePasswordState.busy] the keyboard is off and a progress row says what is running. Back does nothing then,
 * so the owner waits for the change to finish. [onLock] always works. A refused password shows its reason in a banner,
 * and a finished change shows a confirmation with DONE.
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
    copy: ChangeCopy = ChangeCopy.APP_PASSWORD,
) {
    if (state.changed) {
        ChangedContent(copy = copy, onDone = onDone, onLock = onLock, modifier = modifier)
        return
    }
    // Each step opens with the keyboard up. A tap outside the field or the system Back hides it, and a tap on the field brings it back.
    var focused by rememberSaveable(state.step) { mutableStateOf(true) }
    KeyboardHost(focused = focused, onUnfocus = { focused = false }) {
        LenslyScreen(
            title = copy.title,
            modifier = modifier,
            // The arrow stays in place so the title does not move. It does nothing while the change runs.
            onBack = { if (!state.busy) onBack() },
            scrollable = false,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WalletEyebrow("Step ${state.step.ordinal + 1} of 3", modifier = Modifier.weight(1f))
                PillButton(text = "Lock", onClick = onLock)
            }
            Spacer(Modifier.height(LenslySpacing.sm))
            // A fixed height keeps the hint chip in the same place on every step.
            Box(modifier = Modifier.fillMaxWidth().height(HEADLINE_HEIGHT)) {
                DotHeadline(text = headline(state.step, copy))
            }
            PillChip(text = hint(state.step, copy))

            Spacer(Modifier.weight(1f))

            PasswordField(length = state.length, onClick = { focused = true })
            Spacer(Modifier.height(LenslySpacing.sm))
            StatusSlot(state = state)

            Spacer(Modifier.weight(1f))

            BottomSlot(visible = focused) {
                SecureKeyboard(
                    onKey = onKey,
                    onBackspace = onBackspace,
                    onDone = onSubmit,
                    enabled = !state.busy,
                )
            }
            Spacer(Modifier.height(LenslySpacing.md))
        }
    }
}

/**
 * The reserved line under the password field. It holds a progress row while the vault works, or the banner for a
 * refused password. It has one height, so the keyboard does not move when either comes and goes.
 */
@Composable
private fun StatusSlot(state: ChangePasswordState) {
    Box(
        modifier = Modifier.fillMaxWidth().height(STATUS_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        val error = state.error
        when {
            state.busy -> LoadingState(
                label = if (state.step == ChangeStep.Confirm) "Changing the App Password" else "Checking the password",
            )
            error != null -> InlineBanner(message = error.sentence(), tone = BannerTone.Error)
        }
    }
}

@Composable
private fun ChangedContent(copy: ChangeCopy, onDone: () -> Unit, onLock: () -> Unit, modifier: Modifier) {
    LenslyScreen(
        title = copy.title,
        modifier = modifier,
        onBack = null,
        scrollable = false,
        bottomAction = {
            PillButton(text = "Done", onClick = onDone, accent = true, modifier = Modifier.fillMaxWidth())
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WalletEyebrow("Complete", modifier = Modifier.weight(1f))
            PillButton(text = "Lock", onClick = onLock)
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = LenslySuccess,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        DotHeadline(text = "CHANGED")
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = copy.changedText,
            style = MaterialTheme.typography.bodyLarge,
            color = LenslyOnSurface,
        )
    }
}

private fun headline(step: ChangeStep, copy: ChangeCopy): String = when (step) {
    ChangeStep.Current -> "CURRENT"
    ChangeStep.New -> copy.newHeadline
    ChangeStep.Confirm -> "CONFIRM"
}

private fun hint(step: ChangeStep, copy: ChangeCopy): String = when (step) {
    ChangeStep.Current -> "Your password now"
    ChangeStep.New -> copy.newHint
    ChangeStep.Confirm -> "Type the new password again"
}

/** The sentence the owner reads when a new password is refused. */
private fun PolicyError.sentence(): String = when (this) {
    PolicyError.TooShort -> "Use $MIN_PASSWORD_LENGTH or more characters."
    PolicyError.TooWeak -> "Too easy to guess. Add more variety."
    PolicyError.Mismatch -> "The two passwords do not match. Try again."
    PolicyError.SameAsApp -> "That is the App Password. Choose another."
}

private val HEADLINE_HEIGHT = 136.dp
private val STATUS_HEIGHT = 88.dp
