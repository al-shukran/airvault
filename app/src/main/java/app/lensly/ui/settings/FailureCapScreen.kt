package app.lensly.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import app.lensly.core.vault.FailureDelay
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.PasswordStep
import app.lensly.ui.wallet.StepHeader

/**
 * The Failure Delay caps the owner may choose. [capMs] is null for Default, the spec default: 10 s during the learning
 * period and 60 s after it. The others are caps the owner sets, from 1 s to 1 h.
 */
enum class FailureCapChoice(val label: String, val capMs: Long?) {
    ONE_SECOND("1 s", 1_000L),
    FIVE_SECONDS("5 s", 5_000L),
    TEN_SECONDS("10 s", 10_000L),
    THIRTY_SECONDS("30 s", 30_000L),
    ONE_MINUTE("1 min", 60_000L),
    FIVE_MINUTES("5 min", 300_000L),
    FIFTEEN_MINUTES("15 min", 900_000L),
    ONE_HOUR("1 h", 3_600_000L),
    DEFAULT("Default", null);

    companion object {
        /** The choice a saved cap stands for. The app saves only these caps, so any other value reads as Default. */
        fun of(capMs: Long?): FailureCapChoice = entries.firstOrNull { it.capMs == capMs } ?: DEFAULT
    }
}

/** Where the cap change is: the choice, then the App Password. */
sealed interface FailureCapStep {
    /** The owner picks a cap and continues. */
    data object Choose : FailureCapStep

    /** The owner continued with [choice]. The App Password is asked, and [busy] while it is checked. */
    data class Password(val choice: FailureCapChoice, val busy: Boolean) : FailureCapStep
}

/**
 * The Failure Delay cap, in two steps. On the first, the owner picks a cap. A cap below 10 s shows the spec warning, and
 * Continue then reads "Continue anyway", so the owner confirms it. The second step asks for the App Password, and the
 * caller checks it before anything is saved. [saved] is the cap in force, null while it is read. [selected] is the owner's
 * pick, or null before one is made, and the saved cap is shown until then. Continue is off until the pick differs from
 * the saved cap.
 */
@Composable
fun FailureCapScreen(
    step: FailureCapStep,
    saved: FailureCapChoice?,
    selected: FailureCapChoice?,
    passwordLength: Int,
    onSelect: (FailureCapChoice) -> Unit,
    onContinue: () -> Unit,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (step) {
        FailureCapStep.Choose -> ChooseContent(
            saved = saved,
            shown = selected ?: saved,
            onSelect = onSelect,
            onContinue = onContinue,
            onBack = onBack,
            onLock = onLock,
            modifier = modifier,
        )
        is FailureCapStep.Password -> PasswordStep(
            busy = step.busy,
            length = passwordLength,
            onKey = onKey,
            onBackspace = onBackspace,
            onSubmit = onSubmit,
            onBack = onBack,
            onLock = onLock,
            modifier = modifier,
            title = TITLE,
            prompt = "Enter your App Password to save the cap.",
            stepCount = STEP_COUNT,
            step = 2,
        )
    }
}

@Composable
private fun ChooseContent(
    saved: FailureCapChoice?,
    shown: FailureCapChoice?,
    onSelect: (FailureCapChoice) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier,
) {
    val warn = FailureDelay.warnsAt(shown?.capMs)
    val canContinue = shown != null && saved != null && shown != saved
    LenslyScreen(
        title = TITLE,
        modifier = modifier,
        onBack = onBack,
        bottomAction = {
            PillButton(
                text = if (warn) "Continue anyway" else "Continue",
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                accent = true,
                enabled = canContinue,
            )
        },
    ) {
        StepHeader(step = 1, onLock = onLock, count = STEP_COUNT)
        Spacer(Modifier.height(LenslySpacing.lg))
        DotHeadline(text = "DELAY CAP")
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            text = "Each failed check waits longer than the one before, up to this cap. Default is 60 s. " +
                "During the first 7 days after setup, no wait is longer than 10 s.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.lg))
        // Two pills to a row: the labels such as "15 min" need the room.
        for (row in CHOICE_ROWS) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = LenslySpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
            ) {
                for (choice in row) {
                    PillButton(
                        text = choice.label,
                        onClick = { onSelect(choice) },
                        modifier = Modifier.weight(1f).semantics { selected = choice == shown },
                        accent = choice == shown,
                    )
                }
                if (row.size < 2) Spacer(Modifier.weight(1f))
            }
        }
        if (warn) {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(
                title = "Below 10 seconds",
                message = "With a cap this short, each failed check costs less time, so someone guessing your App " +
                    "Password gets more tries in the same time. Continue only if you accept that.",
                tone = BannerTone.Warning,
            )
        }
    }
}

private const val TITLE = "Failure Delay cap"
private const val STEP_COUNT = 2
private val CHOICE_ROWS = FailureCapChoice.entries.chunked(2)
