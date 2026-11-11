package app.lensly.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.core.registry.ProxyPolicy
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutline
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.PasswordStep
import app.lensly.ui.wallet.StepHeader

/** Where the policy change is: the choice, then the App Password. */
sealed interface ProxyPolicyStep {
    /** The owner picks a policy and continues. */
    data object Choose : ProxyPolicyStep

    /** The owner continued with [policy]. The App Password is asked, and [busy] while it is checked. */
    data class Password(val policy: ProxyPolicy, val busy: Boolean) : ProxyPolicyStep
}

/** The policy's name, as the Settings row and the choice show it. */
internal val ProxyPolicy.title: String
    get() = when (this) {
        ProxyPolicy.BIND_TO_IMPLEMENTATION -> "Bind to implementation"
        ProxyPolicy.REFUSE_ALL_PROXIES -> "Refuse all proxies"
        ProxyPolicy.WARN_AND_CONFIRM -> "Warn and confirm"
    }

/** What the policy does with a whitelisted proxy, in plain words. */
internal val ProxyPolicy.explanation: String
    get() = when (this) {
        ProxyPolicy.BIND_TO_IMPLEMENTATION ->
            "The default. Lensly refuses a whitelisted proxy, because a phone with no network cannot check that the proxy " +
                "still runs the code you approved. The refusal names the approved implementation."
        ProxyPolicy.REFUSE_ALL_PROXIES ->
            "Lensly refuses every whitelisted proxy. Choose this if you never want to sign through a proxy."
        ProxyPolicy.WARN_AND_CONFIRM ->
            "Lensly reviews a whitelisted proxy with its approved meaning under a red warning. The contract could have been " +
                "upgraded since you approved it, and Lensly cannot check that offline. You confirm twice before you sign."
    }

/**
 * The proxy policy, in two steps. On the first, the owner picks how a whitelisted proxy is handled, and each choice says in plain
 * words what it does. Continue is off until the pick differs from the saved policy. The second step asks for the App Password, and
 * the caller checks it before anything is saved. [saved] is the policy in force, null while it is read. [selected] is the owner's
 * pick, or null before one is made, and the saved policy is shown until then.
 */
@Composable
fun ProxyPolicyScreen(
    step: ProxyPolicyStep,
    saved: ProxyPolicy?,
    selected: ProxyPolicy?,
    passwordLength: Int,
    onSelect: (ProxyPolicy) -> Unit,
    onContinue: () -> Unit,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (step) {
        ProxyPolicyStep.Choose -> ChooseContent(
            saved = saved,
            shown = selected ?: saved,
            onSelect = onSelect,
            onContinue = onContinue,
            onBack = onBack,
            onLock = onLock,
            modifier = modifier,
        )
        is ProxyPolicyStep.Password -> PasswordStep(
            busy = step.busy,
            length = passwordLength,
            onKey = onKey,
            onBackspace = onBackspace,
            onSubmit = onSubmit,
            onBack = onBack,
            onLock = onLock,
            modifier = modifier,
            title = TITLE,
            prompt = "Enter your App Password to save this policy.",
            stepCount = STEP_COUNT,
            step = 2,
        )
    }
}

@Composable
private fun ChooseContent(
    saved: ProxyPolicy?,
    shown: ProxyPolicy?,
    onSelect: (ProxyPolicy) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier,
) {
    val canContinue = shown != null && saved != null && shown != saved
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
        StepHeader(step = 1, onLock = onLock, count = STEP_COUNT)
        Spacer(Modifier.height(LenslySpacing.lg))
        DotHeadline(text = "PROXIES")
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            text = "A proxy is a contract whose code can be replaced. Its approved meaning can change when that happens, so " +
                "Lensly needs a rule for it. A change here applies to the next request you sign.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.lg))
        ProxyPolicy.entries.forEach { policy ->
            PolicyOption(policy = policy, selected = policy == shown, onClick = { onSelect(policy) })
            Spacer(Modifier.height(LenslySpacing.xs))
        }
    }
}

/** One policy as a choice: its name and what it does. The chosen one is outlined in light grey, and the row reads as a radio. */
@Composable
private fun PolicyOption(policy: ProxyPolicy, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(LenslyRadius.md)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, if (selected) LenslyOnSurface else LenslyOutline, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(LenslySpacing.md),
    ) {
        Text(
            text = policy.title,
            style = MaterialTheme.typography.titleMedium,
            color = LenslyOnSurface,
        )
        Spacer(Modifier.height(LenslySpacing.xxs))
        Text(
            text = policy.explanation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val TITLE = "Proxy policy"
private const val STEP_COUNT = 2
