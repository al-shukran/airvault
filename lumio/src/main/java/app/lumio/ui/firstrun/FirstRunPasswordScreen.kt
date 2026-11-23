package app.lumio.ui.firstrun

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.lensly.core.vault.PasswordPolicy
import app.lensly.core.vault.PolicyReason
import app.lensly.core.vault.PolicyResult
import app.lensly.ui.gate.MIN_PASSWORD_LENGTH
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirField
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirTextAction

/** The first problem with a new App Password. Null means the password is acceptable and the repeat matches it. */
enum class NewPasswordProblem { TooShort, TooWeak, Mismatch }

/** The first problem with [password] and its [repeat], or null when both are acceptable. The policy is checked first, as the gate does. */
fun newPasswordProblem(password: String, repeat: String): NewPasswordProblem? =
    when (val policy = policyOf(password)) {
        is PolicyResult.Refused ->
            if (policy.reason == PolicyReason.TOO_SHORT) NewPasswordProblem.TooShort else NewPasswordProblem.TooWeak
        PolicyResult.Accepted -> if (password == repeat) null else NewPasswordProblem.Mismatch
    }

/** How many of the four strength steps [password] fills: none when empty, all four once the policy accepts it, and one before that. */
fun strengthSegments(password: String): Int = when {
    password.isEmpty() -> 0
    policyOf(password) == PolicyResult.Accepted -> STRENGTH_STEPS
    else -> 1
}

private const val STRENGTH_STEPS = 4

private fun policyOf(password: String): PolicyResult {
    val chars = password.toCharArray()
    return try {
        PasswordPolicy.check(chars)
    } finally {
        chars.fill('\u0000')
    }
}

/**
 * Step I7: the App Password, typed with the system keyboard as the spec asks, in a field and its repeat. The strength line and the hint sit
 * under the first field, and a mismatch shows under the second. Next stays tappable, and [onNext] decides what the problem is.
 */
@Composable
fun FirstRunPasswordScreen(
    password: String,
    repeat: String,
    shown: Boolean,
    problem: NewPasswordProblem?,
    busy: Boolean,
    onPassword: (String) -> Unit,
    onRepeat: (String) -> Unit,
    onToggleShown: () -> Unit,
    onNext: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    FirstRunPage(
        step = FirstRunStep.CreatePassword,
        eyebrow = "Protect · 1 of 3",
        title = "Create Password",
        footer = { ClearAirPrimaryButton(text = "Next", onClick = onNext, enabled = !busy) },
    ) {
        Text(
            text = "It opens Lumio and locks the pairing data on this phone.",
            style = ClearAirType.body,
            color = palette.ink2,
        )
        Spacer(Modifier.height(ClearAirSpacing.lg))
        ClearAirField(
            label = "Password",
            value = password,
            onValueChange = onPassword,
            keyboard = KeyboardType.Password,
            masked = !shown,
            error = policyMessage(problem),
            trailing = { ClearAirTextAction(text = if (shown) "Hide" else "Show", onClick = onToggleShown) },
        )
        Spacer(Modifier.height(ClearAirSpacing.xs))
        StrengthLine(segments = strengthSegments(password))
        Spacer(Modifier.height(ClearAirSpacing.lg))
        ClearAirField(
            label = "Repeat Password",
            value = repeat,
            onValueChange = onRepeat,
            keyboard = KeyboardType.Password,
            masked = !shown,
            error = if (problem == NewPasswordProblem.Mismatch) "The passwords don't match" else null,
        )
        Spacer(Modifier.height(ClearAirSpacing.md))
        NoteLine(text = "Lumio can't recover this password. If you forget it, reset Lumio and pair again. Your keys stay safe on Lensly.")
    }
}

/** The first field's error for a password the policy refuses. A mismatch is shown under the repeat instead. */
private fun policyMessage(problem: NewPasswordProblem?): String? = when (problem) {
    NewPasswordProblem.TooShort -> "Use at least $MIN_PASSWORD_LENGTH characters."
    NewPasswordProblem.TooWeak -> "Too easy to guess. Add variety."
    NewPasswordProblem.Mismatch, null -> null
}

/** Four steps in ink as the password gets stronger, with the hint on the left and the word "Strong" on the right once it is accepted. */
@Composable
private fun StrengthLine(segments: Int) {
    val palette = LocalClearAirPalette.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(ClearAirSpacing.xxs)) {
            repeat(STRENGTH_STEPS) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (index < segments) palette.ink else palette.hairline),
                )
            }
        }
        Spacer(Modifier.height(ClearAirSpacing.xxs))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "Use at least $MIN_PASSWORD_LENGTH characters", style = ClearAirType.label, color = palette.ink2)
            if (segments == STRENGTH_STEPS) Text(text = "Strong", style = ClearAirType.label, color = palette.ink)
        }
    }
}

/** A calm note: an info mark beside the words, so the note never relies on colour alone. */
@Composable
private fun NoteLine(text: String) {
    val palette = LocalClearAirPalette.current
    Row(verticalAlignment = Alignment.Top) {
        ClearAirIcon(glyph = ClearAirGlyph.INFO, color = palette.ink2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(ClearAirSpacing.xs))
        Text(text = text, style = ClearAirType.label, color = palette.ink2)
    }
}
