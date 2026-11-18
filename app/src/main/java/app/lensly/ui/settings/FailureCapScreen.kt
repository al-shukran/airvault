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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.core.vault.FailureDelay
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.SliderSetting
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.PasswordStep
import app.lensly.ui.wallet.StepHeader

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L

/**
 * The wrong password lockout caps the owner may choose, from 10 s to 1 h. Shorter caps are not offered, because they make guessing
 * faster. The default is [DEFAULT], 1 min: the cap a vault that has never saved one uses, which the slider captions as Default.
 * [capMs] is the longest wait after a wrong try.
 */
enum class FailureCapChoice(val label: String, val capMs: Long) {
    TEN_SECONDS("10 s", 10_000L),
    THIRTY_SECONDS("30 s", 30_000L),
    ONE_MINUTE("1 min", 60_000L),
    FIVE_MINUTES("5 min", 300_000L),
    FIFTEEN_MINUTES("15 min", 900_000L),
    ONE_HOUR("1 h", 3_600_000L);

    /** The settings row's value, such as "Up to 1 min". The default cap says so. */
    val summary: String
        get() = "Up to ${formatWait(capMs)}" + if (capMs == FailureDelay.DEFAULT_CAP_MS) " (default)" else ""

    companion object {
        /** The default cap, 1 min, which a vault that has never saved one uses. */
        val DEFAULT: FailureCapChoice = ONE_MINUTE

        /** The choice a saved cap stands for. A missing cap is the default. The app saves only these caps, so any other value reads as the default. */
        fun of(capMs: Long?): FailureCapChoice {
            val cap = capMs ?: FailureDelay.DEFAULT_CAP_MS
            return entries.firstOrNull { it.capMs == cap } ?: DEFAULT
        }
    }
}

/** Where the cap change is: the choice, then the App Password. */
sealed interface FailureCapStep {
    /** The owner picks a cap and continues. */
    data object Choose : FailureCapStep

    /** The owner continued with [choice] and [eraseAfter]. The App Password is asked, and [busy] while it is checked. */
    data class Password(val choice: FailureCapChoice, val eraseAfter: Int, val busy: Boolean) : FailureCapStep
}

/**
 * The wrong password lockout, in two steps. On the first, the owner picks the longest wait on a slider, and how many wrong tries in
 * a row erase the vault. The screen shows the waits that follow each wrong try under the pick. The second step asks for the App
 * Password, and the caller checks it before anything is saved, because the lockout is a security setting. [saved] and [savedErase]
 * are the cap and the erase count in force, null while they are read. [selected] and [selectedErase] are the owner's picks, or null
 * before one is made, and the saved values are shown until then. Continue is off until a pick differs from what is saved.
 */
@Composable
fun FailureCapScreen(
    step: FailureCapStep,
    saved: FailureCapChoice?,
    selected: FailureCapChoice?,
    savedErase: Int?,
    selectedErase: Int?,
    passwordLength: Int,
    onSelect: (FailureCapChoice) -> Unit,
    onSelectErase: (Int) -> Unit,
    onContinue: () -> Unit,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    /** The line near the erase on the App Password step, such as "2 tries left before this vault is erased". */
    warning: String? = null,
    modifier: Modifier = Modifier,
) {
    when (step) {
        FailureCapStep.Choose -> ChooseContent(
            saved = saved,
            shown = selected ?: saved,
            savedErase = savedErase,
            shownErase = selectedErase ?: savedErase,
            onSelect = onSelect,
            onSelectErase = onSelectErase,
            onContinue = onContinue,
            onBack = onBack,
            modifier = modifier,
        )
        is FailureCapStep.Password -> PasswordStep(
            busy = step.busy,
            length = passwordLength,
            onKey = onKey,
            onBackspace = onBackspace,
            onSubmit = onSubmit,
            onBack = onBack,
            modifier = modifier,
            title = TITLE,
            prompt = "Enter your Application Password to save this lockout.",
            stepCount = STEP_COUNT,
            step = 2,
            warning = warning,
        )
    }
}

@Composable
private fun ChooseContent(
    saved: FailureCapChoice?,
    shown: FailureCapChoice?,
    savedErase: Int?,
    shownErase: Int?,
    onSelect: (FailureCapChoice) -> Unit,
    onSelectErase: (Int) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val canContinue = (shown != null && saved != null && shown != saved) ||
        (shownErase != null && savedErase != null && shownErase != savedErase)
    LenslyScreen(
        title = TITLE,
        modifier = modifier,
        onBack = onBack,
        bottomAction = {
            PillButton(
                text = "Continue",
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                accent = true,
                enabled = canContinue,
            )
        },
    ) {
        StepHeader(step = 1, count = STEP_COUNT)
        Spacer(Modifier.height(LenslySpacing.lg))
        Text(
            text = "When someone enters a wrong Application Password, Lensly makes them wait before the next try. Each wrong try " +
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
        SliderSetting(
            title = "Longest Wait",
            options = FailureCapChoice.entries,
            selected = shown,
            label = { it.label },
            onSelect = onSelect,
            caption = { if (it.capMs == FailureDelay.DEFAULT_CAP_MS) "Default" else null },
        )
        if (shown != null) {
            Spacer(Modifier.height(LenslySpacing.md))
            WaitSteps(choice = shown)
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        SliderSetting(
            title = "Erase after",
            options = ERASE_OPTIONS,
            selected = shownErase,
            label = { "$it tries" },
            onSelect = onSelectErase,
            caption = { if (it == FailureDelay.DEFAULT_WIPE_AFTER) "Default" else null },
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        Text(
            text = "After this many wrong tries in a row, Lensly erases this vault. It warns you " +
                "${FailureDelay.WARN_TRIES_LEFT} tries before that. This cannot be turned off.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The erase counts the owner may choose: 3 to 20 wrong tries in a row. */
private val ERASE_OPTIONS: List<Int> = (FailureDelay.MIN_WIPE_AFTER..FailureDelay.MAX_WIPE_AFTER).toList()

/**
 * The waits after the first wrong tries under [choice], one bar to each. The bars grow toward the longest wait, and a bar at the
 * cap is drawn in the accent colour. They move when the choice changes. The row reads as one line to a screen reader.
 */
@Composable
private fun WaitSteps(choice: FailureCapChoice) {
    val steps = waitSteps(choice)
    val cap = choice.capMs
    val tallest = steps.max()
    val description = "Waits after wrong tries: " + steps.joinToString(", ") { formatWait(it) }
    Column(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        Text(
            text = "Wait after Each Wrong Try",
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
                .clip(MaterialTheme.shapes.extraSmall)
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
    val policy = FailureDelay(vaultCreatedWallMs = 0L, configuredCapMs = choice.capMs)
    return (1..STEP_TRIES).map { policy.delayFor(it, nowWallMs = FailureDelay.LEARNING_PERIOD_MS) }
}

/** A wait as the choices name it: "10 s", "1 min", "80 s", "1 h". */
internal fun formatWait(ms: Long): String = when {
    ms < MINUTE_MS || (ms % MINUTE_MS != 0L && ms < HOUR_MS) -> "${ms / 1_000} s"
    ms < HOUR_MS -> "${ms / MINUTE_MS} min"
    else -> "${ms / HOUR_MS} h"
}

private const val TITLE = "Wrong Password Lockout"
private const val STEP_COUNT = 2
/** The height of the tallest bar, of the area that holds a step's label and bar, and of the shortest bar. */
private val BAR_MAX: Dp = 56.dp
private val STEPS_AREA: Dp = 80.dp
private val BAR_MIN: Dp = 6.dp
