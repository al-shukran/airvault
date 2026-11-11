package app.lensly.ui.wallet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.BottomSlot
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.KeyboardHost
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.gate.PasswordField
import app.lensly.ui.theme.LenslySpacing

/** Steps of the optional passphrase screen. Enter and Confirm each take the same typed input. */
enum class PassphraseStep { Ask, Enter, Confirm }

/**
 * UI state for [PassphraseScreen]. Like the gate screens, it holds only the character count. The
 * caller owns the [app.lensly.ui.gate.PasswordBuffer]. [mismatch] is set when the confirm entry differs.
 */
data class PassphraseState(
    val step: PassphraseStep = PassphraseStep.Ask,
    val length: Int = 0,
    val mismatch: Boolean = false,
)

private const val TITLE = "Add passphrase"

/** Enter and Confirm are the two numbered steps. The Ask question before them is optional and not counted. */
private const val ENTRY_STEPS = 2

/** Room for the mismatch banner, so the keyboard does not move when it appears or clears. */
private val MESSAGE_SLOT_HEIGHT = 56.dp

/**
 * Optional BIP39 passphrase. In [PassphraseStep.Ask] the owner chooses yes or skip. In Enter and
 * Confirm the entry goes through [SecureKeyboard]. Skip is always available, and an empty entry is the
 * caller's to treat as "no passphrase". The passphrase is part of the backup, so the screen says it must
 * be written down separately from the words.
 */
@Composable
fun PassphraseScreen(
    state: PassphraseState,
    onYes: () -> Unit,
    onSkip: () -> Unit,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state.step) {
        PassphraseStep.Ask -> PassphraseAsk(onYes = onYes, onSkip = onSkip, modifier = modifier)
        PassphraseStep.Enter, PassphraseStep.Confirm -> PassphraseEntry(
            state = state,
            onSkip = onSkip,
            onKey = onKey,
            onBackspace = onBackspace,
            onSubmit = onSubmit,
            modifier = modifier,
        )
    }
}

@Composable
private fun PassphraseAsk(
    onYes: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier,
) {
    LenslyScreen(
        title = TITLE,
        modifier = modifier,
        bottomAction = {
            WalletPill(
                text = "Yes, add one",
                onClick = onYes,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Primary,
            )
            Spacer(Modifier.height(LenslySpacing.sm))
            WalletPill(
                text = "Skip",
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Outline,
            )
        },
    ) {
        WalletEyebrow("Optional step")
        Spacer(Modifier.height(LenslySpacing.xs))
        DotText(text = "PASSPHRASE", fontSize = 48.sp)
        Spacer(Modifier.height(LenslySpacing.lg))
        Text(
            text = "Without the passphrase, the 24 words restore a different, empty wallet.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(LenslySpacing.lg))
        InlineBanner(
            title = "Part of the backup",
            message = "Write it down separately from the 24 words. A lost passphrase cannot be recovered.",
            tone = BannerTone.Warning,
        )
    }
}

@Composable
private fun PassphraseEntry(
    state: PassphraseState,
    onSkip: () -> Unit,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier,
) {
    val confirming = state.step == PassphraseStep.Confirm
    val step = if (confirming) 2 else 1
    // Each step opens with the keyboard up, so Confirm comes up even when Enter was left with it hidden.
    var focused by rememberSaveable(state.step) { mutableStateOf(true) }
    KeyboardHost(focused = focused, onUnfocus = { focused = false }) {
        LenslyScreen(
            title = TITLE,
            modifier = modifier,
            scrollable = false,
            bottomAction = {
                BottomSlot(visible = focused) {
                    SecureKeyboard(
                        onKey = onKey,
                        onBackspace = onBackspace,
                        onDone = onSubmit,
                    )
                }
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WalletEyebrow("Step $step of $ENTRY_STEPS")
                Spacer(Modifier.weight(1f))
                WalletPill(
                    text = "Skip",
                    onClick = onSkip,
                    modifier = Modifier.widthIn(min = 96.dp),
                    style = WalletPillStyle.Outline,
                    size = WalletPillSize.Compact,
                )
            }
            Spacer(Modifier.height(LenslySpacing.xs))
            StepSegments(count = ENTRY_STEPS, filled = step)
            Spacer(Modifier.height(LenslySpacing.lg))
            // 48sp keeps "PASSPHRASE" on one line on a 720dp-wide phone; the 64sp headline wraps it.
            DotText(text = if (confirming) "CONFIRM" else "PASSPHRASE", fontSize = 48.sp)
            Spacer(Modifier.height(LenslySpacing.sm))
            Text(
                text = if (confirming) "Type it again to check it." else "Part of the backup. Write it down separately.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            PasswordField(length = state.length, onClick = { focused = true })
            Spacer(Modifier.height(LenslySpacing.sm))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MESSAGE_SLOT_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.mismatch -> InlineBanner(message = "Does not match. Try again.", tone = BannerTone.Error)
                    !confirming && state.length == 0 -> Text(
                        text = "Leave empty for no passphrase.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}
