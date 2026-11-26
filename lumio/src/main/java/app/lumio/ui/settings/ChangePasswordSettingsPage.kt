package app.lumio.ui.settings

import app.lumio.DebugDemoData
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.ui.gate.MIN_PASSWORD_LENGTH
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.PasswordChange
import app.lumio.ui.firstrun.NewPasswordProblem
import app.lumio.ui.firstrun.policyMessage
import app.lumio.ui.kit.ClearAirEyeToggle
import app.lumio.ui.kit.ClearAirField
import app.lumio.ui.kit.ClearAirPrimaryButton
import kotlinx.coroutines.launch

/** The words of the three-field password page: Change Password is the App Password's change, and Duress Password sets the second password. */
data class PasswordFormCopy(
    val title: String,
    val newLabel: String,
    val repeatLabel: String,
    val button: String,
    val intro: String? = null,
) {
    companion object {
        val Change = PasswordFormCopy(
            title = "Change Password",
            newLabel = "New Password",
            repeatLabel = "Repeat New Password",
            button = "Change Password",
        )
        val Duress = PasswordFormCopy(
            title = "Duress Password",
            newLabel = "Duress Password",
            repeatLabel = "Repeat Duress Password",
            button = "Set Duress Password",
            intro = "It opens an empty Lumio if you're forced to unlock. It must differ from your Lumio password.",
        )
    }
}

/**
 * Change Password, the App Password's change in Settings → Security: the current password, then the new one and its repeat. The capsule stays off
 * until all three fields are filled, and a hint under the fields says so. A problem shows on the field it belongs to, in the gate's words, and a
 * wrong current password never says how close a lockout is. [onSubmit] runs the change, [onChanged] leaves the page once it is made, and
 * [onLocked] and [onUnavailable] take the owner to the gate's answer for a vault that cannot be used. [copy] turns the page into the Duress Password's
 * set, which asks for the current password in the same way.
 */
@Composable
fun ChangePasswordSettingsPage(
    offline: String?,
    onSubmit: suspend (current: String, password: String, repeat: String) -> PasswordChange,
    onChanged: () -> Unit,
    onLocked: () -> Unit,
    onUnavailable: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    copy: PasswordFormCopy = PasswordFormCopy.Change,
) {
    val palette = LocalClearAirPalette.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(DebugDemoData.text("current")) }
    var password by remember { mutableStateOf(DebugDemoData.text("new")) }
    var repeat by remember { mutableStateOf(DebugDemoData.text("repeat")) }
    var shown by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // What the last submit found and left on the page. Editing a field clears it, so an old problem does not stay on screen.
    var problem by remember { mutableStateOf<PasswordChange?>(null) }
    val filled = current.isNotEmpty() && password.isNotEmpty() && repeat.isNotEmpty()
    val refused = (problem as? PasswordChange.NewPassword)?.problem

    // The fields hold the App Password as Strings, which the JVM cannot zero, so clearing them only ends their life on this page.
    fun clearFields() {
        current = ""
        password = ""
        repeat = ""
    }
    DisposableEffect(Unit) { onDispose { clearFields() } }

    fun submit() {
        if (busy || !filled) return
        busy = true
        problem = null
        scope.launch {
            val outcome = onSubmit(current, password, repeat)
            busy = false
            // A wrong current password keeps the three fields as they were typed, so the owner corrects the one that is wrong.
            if (outcome != PasswordChange.WrongCurrent) clearFields()
            when (outcome) {
                PasswordChange.Changed -> onChanged()
                PasswordChange.Locked -> onLocked()
                PasswordChange.Unavailable -> onUnavailable()
                else -> problem = outcome
            }
        }
    }

    val reveal: @Composable () -> Unit = {
        ClearAirEyeToggle(shown = shown, onToggle = { shown = !shown })
    }

    SettingsFrame(title = copy.title, offline = offline, onBack = onBack, onLock = onLock) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter)) {
            copy.intro?.let {
                Text(text = it, style = ClearAirType.body, color = palette.ink2)
                Spacer(Modifier.height(ClearAirSpacing.lg))
            }
            ClearAirField(
                label = "Current Password",
                value = current,
                onValueChange = {
                    current = it
                    problem = null
                },
                keyboard = KeyboardType.Password,
                masked = !shown,
                error = if (problem == PasswordChange.WrongCurrent) WRONG_CURRENT_PASSWORD else null,
                trailing = reveal,
            )
            Spacer(Modifier.height(ClearAirSpacing.lg))
            ClearAirField(
                label = copy.newLabel,
                value = password,
                onValueChange = {
                    password = it
                    problem = null
                },
                placeholder = "At least $MIN_PASSWORD_LENGTH characters",
                keyboard = KeyboardType.Password,
                masked = !shown,
                error = policyMessage(refused),
                trailing = reveal,
            )
            Spacer(Modifier.height(ClearAirSpacing.lg))
            ClearAirField(
                label = copy.repeatLabel,
                value = repeat,
                onValueChange = {
                    repeat = it
                    problem = null
                },
                keyboard = KeyboardType.Password,
                masked = !shown,
                onSubmit = { submit() },
                error = if (refused == NewPasswordProblem.Mismatch) "The passwords don't match" else null,
                trailing = reveal,
            )
            Spacer(Modifier.height(ClearAirSpacing.lg))
            if (!filled) {
                Text(text = "Fill in all three fields to continue", style = ClearAirType.body, color = palette.ink2)
                Spacer(Modifier.height(ClearAirSpacing.xs))
            }
            ClearAirPrimaryButton(
                text = copy.button,
                onClick = { submit() },
                modifier = Modifier.fillMaxWidth(),
                enabled = filled && !busy,
            )
        }
    }
}
