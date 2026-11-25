package app.lumio.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.lensly.ui.flow.PasswordCheck
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.config.AutoLock
import app.lumio.ui.kit.ChoiceGroup
import app.lumio.ui.kit.ChoiceRow
import app.lumio.ui.kit.ClearAirChoiceSheet
import app.lumio.ui.kit.ClearAirDialog
import app.lumio.ui.kit.ClearAirField
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirGlyphDisc
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.ui.kit.ClearAirTextAction
import kotlinx.coroutines.launch

/** The error under the password when it is not the App Password. It is the same words the Change Password page uses. */
internal const val WRONG_CURRENT_PASSWORD = "That isn't your current password"

/** The Auto-Lock row of the Security page: its value in words. [onOpen] asks the page to open [AutoLockSheet]. */
@Composable
fun AutoLockRow(current: AutoLock, onOpen: () -> Unit) {
    val palette = LocalClearAirPalette.current
    ClearAirRow(
        title = "Auto-Lock",
        description = "When Lumio locks itself",
        leading = { ClearAirGlyphDisc(ClearAirGlyph.CLOCK, size = 19.dp) },
        trailing = { Text(text = current.label, style = ClearAirType.body, color = palette.ink) },
        onClick = onOpen,
    )
}

/**
 * The radio sheet of the Auto-Lock choices, like Currency's, over the Security page. It closes on a choice, and [onPick] gets a choice other than the
 * [current] one. The page asks for the App Password in [AutoLockPasswordDialog] before it applies that choice.
 */
@Composable
fun AutoLockSheet(visible: Boolean, current: AutoLock, onClose: () -> Unit, onPick: (AutoLock) -> Unit) {
    ClearAirSheet(visible = visible, onClose = onClose) {
        ClearAirChoiceSheet(
            title = "Auto-Lock",
            groups = listOf(
                ChoiceGroup(
                    null,
                    AutoLock.entries.map { choice ->
                        ChoiceRow(
                            title = choice.label,
                            selected = choice == current,
                            onPick = {
                                onClose()
                                if (choice != current) onPick(choice)
                            },
                        )
                    },
                ),
            ),
        )
    }
}

/** The App Password step of an Auto-Lock change: one masked field, cleared on each try, and the change once the password is accepted. */
@Composable
internal fun AutoLockPasswordDialog(
    choice: AutoLock,
    checkPassword: suspend (CharArray) -> PasswordCheck,
    onClose: () -> Unit,
    onAccepted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { password = "" } }

    fun submit() {
        if (busy || password.isEmpty()) return
        val entered = password.toCharArray()
        password = ""
        error = null
        busy = true
        scope.launch {
            val check = checkPassword(entered)
            busy = false
            when (check) {
                PasswordCheck.Accepted -> onAccepted()
                PasswordCheck.Rejected -> error = WRONG_CURRENT_PASSWORD
                PasswordCheck.Unavailable -> onClose()
            }
        }
    }

    ClearAirDialog(
        title = "Auto-Lock",
        text = "Enter your current password to set Auto-Lock to ${choice.label}.",
        onDismiss = onClose,
    ) {
        ClearAirField(
            label = "Current Password",
            value = password,
            onValueChange = {
                password = it
                error = null
            },
            keyboard = KeyboardType.Password,
            masked = true,
            error = error,
            onSubmit = { submit() },
        )
        ClearAirPrimaryButton(text = "Change", onClick = { submit() }, enabled = password.isNotEmpty() && !busy)
        ClearAirTextAction(text = "Cancel", onClick = onClose)
    }
}
