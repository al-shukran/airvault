package app.lensly.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.flow.RevealPart
import app.lensly.ui.flow.RevealStep
import app.lensly.ui.gate.DotProgress
import app.lensly.ui.gate.PasswordField
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.SpaceMonoFamily

/**
 * Show Secret, one step at a time: the App Password, then the warning with SHOW, then the secret one page at a time.
 * Nothing here holds the secret. [step] carries it, and [RevealStep.Cleared] draws nothing, since the route leaves.
 *
 * The password keypad is the in-app one, as on every entry screen. The secret is plain text on screen, and it cannot be
 * selected, copied or autofilled.
 */
@Composable
fun ShowSecretScreen(
    step: RevealStep,
    passwordLength: Int,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onShow: () -> Unit,
    onMove: (Int) -> Unit,
    onSelect: (RevealPart) -> Unit,
    onDone: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (step) {
        is RevealStep.Password -> PasswordStep(
            busy = step.busy,
            length = passwordLength,
            onKey = onKey,
            onBackspace = onBackspace,
            onSubmit = onSubmit,
            onBack = onDone,
            onLock = onLock,
            modifier = modifier,
        )
        RevealStep.Warning -> WarningStep(onShow = onShow, onBack = onDone, onLock = onLock, modifier = modifier)
        is RevealStep.Revealed -> RevealedStep(
            step = step,
            onMove = onMove,
            onSelect = onSelect,
            onDone = onDone,
            onLock = onLock,
            modifier = modifier,
        )
        is RevealStep.Cleared -> Unit
    }
}

@Composable
private fun PasswordStep(
    busy: Boolean,
    length: Int,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier,
) {
    WalletScaffold(
        modifier = modifier,
        scrollable = false,
        footer = {
            SecureKeyboard(
                onKey = onKey,
                onBackspace = onBackspace,
                onDone = onSubmit,
                enabled = !busy,
            )
        },
    ) {
        Spacer(Modifier.height(16.dp))
        NavRow(onBack = onBack, onLock = onLock)
        Spacer(Modifier.height(20.dp))
        WalletEyebrow("Show secret")
        Spacer(Modifier.height(8.dp))
        DotHeadline(text = "CONFIRM")
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Enter your App Password to show the secret.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.weight(1f))

        PasswordField(length = length)
        Spacer(Modifier.height(12.dp))
        // Reserved height, so the keyboard does not move while the progress row comes and goes. A wrong password shows
        // nothing, as at the gate.
        Box(
            modifier = Modifier.fillMaxWidth().height(PROGRESS_SLOT_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) DotProgress()
        }

        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun WarningStep(
    onShow: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier,
) {
    WalletScaffold(
        modifier = modifier,
        footer = {
            WalletPill(
                text = "Show",
                onClick = onShow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                style = WalletPillStyle.Primary,
            )
        },
    ) {
        Spacer(Modifier.height(16.dp))
        NavRow(onBack = onBack, onLock = onLock)
        Spacer(Modifier.height(20.dp))
        WalletEyebrow("Show secret")
        Spacer(Modifier.height(8.dp))
        DotHeadline(text = "WARNING")
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Anyone who sees these can take your funds. Make sure no one is watching. Never photograph or share them.",
            style = MaterialTheme.typography.bodyLarge,
            color = LenslyOnSurface,
        )
    }
}

@Composable
private fun RevealedStep(
    step: RevealStep.Revealed,
    onMove: (Int) -> Unit,
    onSelect: (RevealPart) -> Unit,
    onDone: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier,
) {
    val secret = step.secret
    WalletScaffold(
        modifier = modifier,
        scrollable = false,
        footer = {
            if (secret.isKey) {
                WalletPill(
                    text = "Done",
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    style = WalletPillStyle.Primary,
                )
            } else {
                val last = step.index == secret.pageCount(step.part) - 1
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    WalletPill(
                        text = "Previous",
                        onClick = { onMove(-1) },
                        modifier = Modifier.weight(1f),
                        style = WalletPillStyle.Secondary,
                        enabled = step.index > 0,
                    )
                    WalletPill(
                        text = if (last) "Done" else "Next",
                        onClick = { if (last) onDone() else onMove(1) },
                        modifier = Modifier.weight(1f),
                        style = WalletPillStyle.Primary,
                    )
                }
            }
        },
    ) {
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WalletEyebrow("Hides in ${step.secondsLeft} s")
            Spacer(Modifier.weight(1f))
            WalletPill(
                text = "Hide",
                onClick = onDone,
                style = WalletPillStyle.Outline,
                size = WalletPillSize.Compact,
            )
            WalletPill(
                text = "Lock",
                onClick = onLock,
                style = WalletPillStyle.Outline,
                size = WalletPillSize.Compact,
            )
        }
        if (secret.hasPassphrase) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WalletPill(
                    text = "Words",
                    onClick = { onSelect(RevealPart.WORDS) },
                    style = if (step.part == RevealPart.WORDS) WalletPillStyle.Primary else WalletPillStyle.Outline,
                    size = WalletPillSize.Compact,
                )
                WalletPill(
                    text = "Passphrase",
                    onClick = { onSelect(RevealPart.PASSPHRASE) },
                    style = if (step.part == RevealPart.PASSPHRASE) WalletPillStyle.Primary else WalletPillStyle.Outline,
                    size = WalletPillSize.Compact,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        if (secret.isKey) {
            WalletEyebrow("Single key. Check it against your backup")
            Spacer(Modifier.height(8.dp))
            WalletWarningChip(text = "Never photograph")
            Spacer(Modifier.height(20.dp))
            HashLines(hex = secret.page(RevealPart.KEY, 0))
        } else {
            val isPassphrase = step.part == RevealPart.PASSPHRASE
            val count = secret.pageCount(step.part)
            val title = if (isPassphrase) "Passphrase" else "Recovery phrase"
            WalletEyebrow("$title. Word ${step.index + 1} of $count")
            Spacer(Modifier.height(8.dp))
            WalletWarningChip(text = "Never photograph")
            Spacer(Modifier.height(20.dp))
            WordPage(text = secret.page(step.part, step.index), passphrase = isPassphrase)
        }
    }
}

/**
 * One word, large. A BIP39 word is lowercase by definition, so it is uppercased like the seed display. A passphrase word is
 * shown exactly as typed, since its letter case is part of it, and it wraps when it is long.
 */
@Composable
private fun WordPage(text: String, passphrase: Boolean) {
    if (passphrase) {
        Text(
            text = text,
            style = TextStyle(fontFamily = SpaceMonoFamily, fontSize = 32.sp),
            color = LenslyOnSurface,
        )
    } else {
        DotText(text = text.uppercase(), fontSize = 48.sp)
    }
}

@Composable
private fun NavRow(onBack: () -> Unit, onLock: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WalletPill(
            text = "Back",
            onClick = onBack,
            style = WalletPillStyle.Outline,
            size = WalletPillSize.Compact,
        )
        Spacer(Modifier.weight(1f))
        WalletPill(
            text = "Lock",
            onClick = onLock,
            style = WalletPillStyle.Outline,
            size = WalletPillSize.Compact,
        )
    }
}

private val PROGRESS_SLOT_HEIGHT = 12.dp
