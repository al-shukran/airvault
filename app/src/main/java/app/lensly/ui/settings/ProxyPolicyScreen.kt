package app.lensly.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.core.registry.ProxyPolicy
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.IslandDivider
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.RadioRow
import app.lensly.ui.components.SettingsIsland
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
        ProxyPolicy.BIND_TO_IMPLEMENTATION -> "Bind to Implementation"
        ProxyPolicy.REFUSE_ALL_PROXIES -> "Refuse All Proxies"
        ProxyPolicy.WARN_AND_CONFIRM -> "Warn and Confirm"
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
    modifier: Modifier = Modifier,
) {
    when (step) {
        ProxyPolicyStep.Choose -> ChooseContent(
            saved = saved,
            shown = selected ?: saved,
            onSelect = onSelect,
            onContinue = onContinue,
            onBack = onBack,
            modifier = modifier,
        )
        is ProxyPolicyStep.Password -> PasswordStep(
            busy = step.busy,
            length = passwordLength,
            onKey = onKey,
            onBackspace = onBackspace,
            onSubmit = onSubmit,
            onBack = onBack,
            modifier = modifier,
            title = TITLE,
            prompt = "Enter your Application Password to save this policy.",
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
        StepHeader(step = 1, count = STEP_COUNT)
        Spacer(Modifier.height(LenslySpacing.lg))
        DotHeadline(text = "Proxies")
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            text = "A proxy is a contract whose code can be replaced. Its approved meaning can change when that happens, so " +
                "Lensly needs a rule for it. A change here applies to the next request you sign.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsIsland("Policy") {
            ProxyPolicy.entries.forEachIndexed { index, policy ->
                if (index > 0) IslandDivider()
                RadioRow(
                    title = policy.title,
                    description = policy.explanation,
                    selected = policy == shown,
                    onSelect = { onSelect(policy) },
                    divided = false,
                )
            }
        }
    }
}

private const val TITLE = "Proxy Policy"
private const val STEP_COUNT = 2
