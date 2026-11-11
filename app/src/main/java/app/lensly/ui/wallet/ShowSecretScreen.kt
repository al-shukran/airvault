package app.lensly.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.DotProgress
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.flow.RevealPart
import app.lensly.ui.flow.RevealStep
import app.lensly.ui.gate.PasswordField
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyPillShape
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceContainer
import app.lensly.ui.theme.LenslySurfaceHighest
import app.lensly.ui.theme.LenslyWarning
import app.lensly.ui.theme.SpaceMonoFamily

private const val TITLE = "Show secret"

/** The three steps: App Password, warning, and the secret. */
private const val STEP_COUNT = 3

/** At or below this many seconds the countdown turns amber, so the owner notices it is about to hide. */
private const val LOW_TIME_SECONDS = 10

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
    LenslyScreen(
        title = TITLE,
        onBack = onBack,
        modifier = modifier,
        scrollable = false,
        bottomAction = {
            SecureKeyboard(
                onKey = onKey,
                onBackspace = onBackspace,
                onDone = onSubmit,
                enabled = !busy,
            )
        },
    ) {
        StepHeader(step = 1, onLock = onLock)
        Spacer(Modifier.height(LenslySpacing.lg))
        DotHeadline(text = "PASSWORD")
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            text = "Enter your App Password to show the secret.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.weight(1f))

        PasswordField(length = length)
        Spacer(Modifier.height(LenslySpacing.sm))
        // Reserved height, so the keyboard does not move while the progress row comes and goes. A wrong password shows
        // nothing, as at the gate.
        Box(
            modifier = Modifier.fillMaxWidth().height(PROGRESS_SLOT_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DotProgress(description = "Checking password")
                    Spacer(Modifier.width(LenslySpacing.xs))
                    Text(
                        text = "CHECKING",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
    LenslyScreen(
        title = TITLE,
        onBack = onBack,
        modifier = modifier,
        bottomAction = {
            WalletPill(
                text = "Show",
                onClick = onShow,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Primary,
            )
        },
    ) {
        StepHeader(step = 2, onLock = onLock)
        Spacer(Modifier.height(LenslySpacing.lg))
        DotHeadline(text = "WARNING")
        Spacer(Modifier.height(LenslySpacing.lg))
        InlineBanner(
            title = "Keep it private",
            message = "Anyone who sees these can take your funds. Make sure no one is watching. " +
                "Never photograph or share them.",
            tone = BannerTone.Warning,
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
    val last = !secret.isKey && step.index == secret.pageCount(step.part) - 1
    LenslyScreen(
        title = TITLE,
        modifier = modifier,
        scrollable = false,
        bottomAction = {
            if (secret.isKey) {
                WalletPill(
                    text = "Done",
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                    style = WalletPillStyle.Primary,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Hides in ${step.secondsLeft} s".uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = if (step.secondsLeft <= LOW_TIME_SECONDS) LenslyWarning
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            WalletPill(
                text = "Hide",
                onClick = onDone,
                style = WalletPillStyle.Outline,
                size = WalletPillSize.Compact,
            )
            Spacer(Modifier.width(LenslySpacing.xs))
            WalletPill(
                text = "Lock",
                onClick = onLock,
                style = WalletPillStyle.Outline,
                size = WalletPillSize.Compact,
            )
        }
        if (secret.hasPassphrase) {
            Spacer(Modifier.height(LenslySpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
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
        Spacer(Modifier.height(LenslySpacing.lg))
        if (secret.isKey) {
            WalletEyebrow("Single key. Check it against your backup")
            Spacer(Modifier.height(LenslySpacing.xs))
            WalletWarningChip(text = "Never photograph")
            Spacer(Modifier.height(LenslySpacing.lg))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(LenslyRadius.lg))
                    .background(LenslySurfaceContainer)
                    .padding(LenslySpacing.md),
            ) {
                HashLines(hex = secret.page(RevealPart.KEY, 0))
            }
        } else {
            val isPassphrase = step.part == RevealPart.PASSPHRASE
            val count = secret.pageCount(step.part)
            val title = if (isPassphrase) "Passphrase" else "Recovery phrase"
            WalletEyebrow("$title. Word ${step.index + 1} of $count")
            Spacer(Modifier.height(LenslySpacing.xs))
            StepSegments(count = count, filled = step.index + 1)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                WordPage(text = secret.page(step.part, step.index), passphrase = isPassphrase)
            }
            WalletWarningChip(text = "Never photograph", modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** The step line at the top of a step: "Step N of 3", a Lock pill, and the progress bar underneath. */
@Composable
private fun StepHeader(step: Int, onLock: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WalletEyebrow("Step $step of $STEP_COUNT")
        Spacer(Modifier.weight(1f))
        WalletPill(
            text = "Lock",
            onClick = onLock,
            style = WalletPillStyle.Outline,
            size = WalletPillSize.Compact,
        )
    }
    Spacer(Modifier.height(LenslySpacing.xs))
    StepSegments(count = STEP_COUNT, filled = step)
}

/**
 * Progress through a flow with a known number of steps or pages: [count] short bars, the first [filled] of them lit.
 * TalkBack reads it as "[filled] of [count]".
 */
@Composable
internal fun StepSegments(count: Int, filled: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$filled of $count" },
        horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
    ) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(SEGMENT_HEIGHT)
                    .clip(LenslyPillShape)
                    .background(if (index < filled) LenslyOnSurface else LenslySurfaceHighest),
            )
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
            style = TextStyle(fontFamily = SpaceMonoFamily, fontSize = 40.sp),
            color = LenslyOnSurface,
            textAlign = TextAlign.Center,
        )
    } else {
        DotText(text = text.uppercase(), fontSize = 64.sp)
    }
}

private val PROGRESS_SLOT_HEIGHT = 24.dp
private val SEGMENT_HEIGHT = 3.dp
private val SEGMENT_GAP = 4.dp
