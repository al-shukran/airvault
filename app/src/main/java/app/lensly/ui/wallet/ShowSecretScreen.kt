package app.lensly.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.flow.RevealPart
import app.lensly.ui.flow.RevealStep
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceContainer
import app.lensly.ui.theme.LenslyWarning
import app.lensly.ui.theme.SpaceMonoFamily

private const val TITLE = "Show secret"
private const val SHOW_PROMPT = "Enter your Application Password to show the secret."

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
    modifier: Modifier = Modifier,
    /** The Done of the last page of the words. It runs in place of [onDone] there, so a caller can record the backup first. */
    onFinish: () -> Unit = onDone,
) {
    when (step) {
        is RevealStep.Password -> PasswordStep(
            busy = step.busy,
            length = passwordLength,
            onKey = onKey,
            onBackspace = onBackspace,
            onSubmit = onSubmit,
            onBack = onDone,
            modifier = modifier,
            title = TITLE,
            prompt = SHOW_PROMPT,
            stepCount = STEP_COUNT,
        )
        RevealStep.Warning -> WarningStep(onShow = onShow, onBack = onDone, modifier = modifier)
        is RevealStep.Revealed -> RevealedStep(
            step = step,
            onMove = onMove,
            onSelect = onSelect,
            onDone = onDone,
            onFinish = onFinish,
            modifier = modifier,
        )
        is RevealStep.Cleared -> Unit
    }
}

@Composable
private fun WarningStep(
    onShow: () -> Unit,
    onBack: () -> Unit,
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
        StepHeader(step = 2, count = STEP_COUNT)
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
    onFinish: () -> Unit,
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
                        onClick = { if (last) onFinish() else onMove(1) },
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
