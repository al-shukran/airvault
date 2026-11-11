package app.lensly.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.core.vault.FailureDelay
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.PasswordStep
import app.lensly.ui.wallet.StepHeader

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L

/**
 * The wrong password lockout caps the owner may choose. [capMs] is null for Default, the spec default: 10 s during the learning
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

    /** The settings row's value, such as "Up to 1 min". Default names the wait it stands for. */
    val summary: String
        get() = "Up to ${formatWait(capMs ?: FailureDelay.DEFAULT_CAP_MS)}" + if (capMs == null) " (default)" else ""

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
 * The wrong password lockout, which is the Failure Delay cap, in two steps. On the first, the owner picks the longest wait. The
 * screen shows the waits that follow each wrong try under the pick, and a cap below 10 s shows the spec warning, and Continue
 * then reads "Continue anyway", so the owner confirms it. The second step asks for the App Password, and the caller checks it
 * before anything is saved. [saved] is the cap in force, null while it is read. [selected] is the owner's pick, or null before
 * one is made, and the saved cap is shown until then. Continue is off until the pick differs from the saved cap.
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
            prompt = "Enter your App Password to save this lockout.",
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
        DotHeadline(text = "LONGEST WAIT")
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            text = "When someone enters a wrong App Password, Lensly makes them wait before the next try. Each wrong try " +
                "doubles the wait: 10 s, 20 s, 40 s… This setting sets the longest wait.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        Text(
            text = "During the first 7 days after setup, no wait is longer than 10 s.",
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
        if (shown != null) {
            Spacer(Modifier.height(LenslySpacing.md))
            WaitSteps(choice = shown)
        }
        if (warn) {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(
                title = "Below 10 seconds",
                message = "Short waits make guessing your password faster. With a cap this short, each wrong try costs " +
                    "less time, so someone guessing gets more tries in the same time. Continue only if you accept that.",
                tone = BannerTone.Warning,
            )
        }
    }
}

/**
 * The waits after the first wrong tries under [choice], one bar to each. The bars grow toward the longest wait, and a bar at the
 * cap is drawn in the accent colour. They move when the choice changes. The row reads as one line to a screen reader.
 */
@Composable
private fun WaitSteps(choice: FailureCapChoice) {
    val steps = waitSteps(choice)
    val cap = choice.capMs ?: FailureDelay.DEFAULT_CAP_MS
    val tallest = steps.max()
    val description = "Waits after wrong tries: " + steps.joinToString(", ") { formatWait(it) }
    Column(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        Text(
            text = "WAIT AFTER EACH WRONG TRY",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        Row(
            modifier = Modifier.fillMaxWidth().height(STEPS_AREA),
            horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (wait in steps) {
                WaitBar(wait = wait, tallest = tallest, atCap = wait == cap, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** One step of [WaitSteps]: its wait above a bar whose height is the share of [tallest] that [wait] takes. */
@Composable
private fun WaitBar(wait: Long, tallest: Long, atCap: Boolean, modifier: Modifier) {
    val height by animateDpAsState(
        targetValue = BAR_MIN + (BAR_MAX - BAR_MIN) * (wait.toFloat() / tallest),
        label = "wait-bar",
    )
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formatWait(wait),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(LenslySpacing.xxs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(LenslyRadius.xs))
                .background(if (atCap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

/** How many wrong tries the wait steps show. */
private const val STEP_TRIES = 5

/**
 * The wait after each of the first [STEP_TRIES] wrong tries under [choice], from the domain's own policy. It is read at a time
 * past the 7-day learning period, so each wait is the one the choice sets, not the 10 s cap of the first week.
 */
internal fun waitSteps(choice: FailureCapChoice): List<Long> {
    val policy = FailureDelay(vaultCreatedWallMs = 0L, configuredCapMs = choice.capMs ?: FailureDelay.DEFAULT_CAP_MS)
    return (1..STEP_TRIES).map { policy.delayFor(it, nowWallMs = FailureDelay.LEARNING_PERIOD_MS) }
}

/** A wait as the choices name it: "10 s", "1 min", "80 s", "1 h". */
internal fun formatWait(ms: Long): String = when {
    ms < MINUTE_MS || (ms % MINUTE_MS != 0L && ms < HOUR_MS) -> "${ms / 1_000} s"
    ms < HOUR_MS -> "${ms / MINUTE_MS} min"
    else -> "${ms / HOUR_MS} h"
}

private const val TITLE = "Wrong password lockout"
private const val STEP_COUNT = 2
private val CHOICE_ROWS = FailureCapChoice.entries.chunked(2)
/** The height of the tallest bar, of the area that holds a step's label and bar, and of the shortest bar. */
private val BAR_MAX: Dp = 56.dp
private val STEPS_AREA: Dp = 80.dp
private val BAR_MIN: Dp = 6.dp
