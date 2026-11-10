package app.lensly.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.gate.PasswordField

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
    WalletScaffold(
        modifier = modifier,
        footer = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WalletPill(
                    text = "Yes, add one",
                    onClick = onYes,
                    modifier = Modifier.fillMaxWidth(),
                    style = WalletPillStyle.Primary,
                )
                WalletPill(
                    text = "Skip",
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth(),
                    style = WalletPillStyle.Outline,
                )
            }
        },
    ) {
        Spacer(Modifier.height(24.dp))
        WalletEyebrow("Optional step")
        Spacer(Modifier.height(12.dp))
        DotHeadline(text = "ADD PASSPHRASE?")
        Spacer(Modifier.height(16.dp))
        WalletWarningChip(text = "Part of the backup")
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Without the passphrase, the 24 words restore a different, empty wallet.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Write it down separately from the 24 words. A lost passphrase cannot be recovered.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    WalletScaffold(
        modifier = modifier,
        scrollable = false,
        footer = {
            SecureKeyboard(
                onKey = onKey,
                onBackspace = onBackspace,
                onDone = onSubmit,
            )
        },
    ) {
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WalletEyebrow(if (confirming) "Step 2 of 2" else "Step 1 of 2")
            Spacer(Modifier.weight(1f))
            WalletPill(
                text = "Skip",
                onClick = onSkip,
                modifier = Modifier.widthIn(min = 96.dp),
                style = WalletPillStyle.Outline,
                size = WalletPillSize.Compact,
            )
        }
        Spacer(Modifier.height(12.dp))
        // 48sp keeps "PASSPHRASE" on one line on a 720dp-wide phone; the 64sp headline wraps it.
        DotText(text = if (confirming) "CONFIRM" else "PASSPHRASE", fontSize = 48.sp)
        Spacer(Modifier.height(16.dp))
        WalletWarningChip(text = "Part of the backup. Write it down separately")
        Spacer(Modifier.weight(1f))
        PasswordField(length = state.length)
        Spacer(Modifier.height(12.dp))
        WalletError(message = if (state.mismatch) "Does not match. Try again." else null)
        Spacer(Modifier.weight(1f))
    }
}
