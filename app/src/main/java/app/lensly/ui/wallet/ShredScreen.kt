package app.lensly.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.flow.ShredStep
import app.lensly.ui.theme.LenslySpacing

/** The words of one destructive screen: its title, the App Password prompt, the warning, and the label of the action. */
data class ShredCopy(
    val title: String,
    val prompt: String,
    val warningTitle: String,
    val warning: String,
    val action: String,
)

/** Delete Wallet and Wipe have two steps: the App Password, then the warning with its confirmation. */
private const val SHRED_STEP_COUNT = 2

/**
 * A destructive action, one step at a time: the App Password, then the warning with CANCEL and the action. Nothing here
 * erases anything, since the route does that on confirm. [ShredStep.Ended] draws nothing, since the route leaves.
 */
@Composable
fun ShredScreen(
    step: ShredStep,
    copy: ShredCopy,
    passwordLength: Int,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (step) {
        is ShredStep.Password -> PasswordStep(
            busy = step.busy,
            length = passwordLength,
            onKey = onKey,
            onBackspace = onBackspace,
            onSubmit = onSubmit,
            onBack = onBack,
            onLock = onLock,
            modifier = modifier,
            title = copy.title,
            prompt = copy.prompt,
            stepCount = SHRED_STEP_COUNT,
        )
        is ShredStep.Confirm -> ConfirmStep(
            busy = step.busy,
            copy = copy,
            onConfirm = onConfirm,
            onBack = onBack,
            onLock = onLock,
            modifier = modifier,
        )
        is ShredStep.Ended -> Unit
    }
}

@Composable
private fun ConfirmStep(
    busy: Boolean,
    copy: ShredCopy,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier,
) {
    LenslyScreen(
        title = copy.title,
        onBack = onBack,
        modifier = modifier,
        bottomAction = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
            ) {
                WalletPill(
                    text = "Cancel",
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                    style = WalletPillStyle.Secondary,
                    enabled = !busy,
                )
                WalletPill(
                    text = copy.action,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    style = WalletPillStyle.Accent,
                    enabled = !busy,
                )
            }
        },
    ) {
        StepHeader(step = 2, onLock = onLock, count = SHRED_STEP_COUNT)
        Spacer(Modifier.height(LenslySpacing.lg))
        DotHeadline(text = "WARNING")
        Spacer(Modifier.height(LenslySpacing.lg))
        InlineBanner(
            title = copy.warningTitle,
            message = copy.warning,
            tone = BannerTone.Error,
        )
    }
}
