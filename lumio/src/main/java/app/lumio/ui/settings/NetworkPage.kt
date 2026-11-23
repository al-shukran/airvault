package app.lumio.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.ui.kit.ClearAirField
import app.lumio.ui.kit.ClearAirListRow
import app.lumio.ui.kit.ClearAirNote
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirRadio

/**
 * The Network page (B4) of one chain: its built-in RPC address, which cannot be deleted, and the custom address the owner set, if any. The radio
 * on the address in use is filled, and tapping the built-in row returns to it at once. A new custom address is typed in the field and checked by
 * [onAddCustom] when the owner presses Add; a refusal shows under the field. [custom] is the address in use, or null for the built-in one, and
 * [busy] holds the page while a change runs. The typed address is cleared once the address in use changes.
 */
@Composable
fun NetworkPage(
    chainName: String,
    chainLine: String,
    custom: String?,
    busy: Boolean,
    notice: String?,
    offline: String?,
    onUseBuiltIn: () -> Unit,
    onAddCustom: (String) -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(custom) { draft = "" }
    SettingsFrame(title = chainName, offline = offline, onBack = onBack, onLock = onLock, backLabel = "Networks") {
        ClearAirNote(text = chainLine, modifier = Modifier.padding(top = ClearAirSpacing.md))
        SettingsGroup(title = "Default RPC") {
            ClearAirListRow(
                title = "Built-in RPC",
                description = "Built In · Can't be deleted",
                trailing = { ClearAirRadio(selected = custom == null) },
                onClick = if (busy || custom == null) null else onUseBuiltIn,
            )
        }
        custom?.let {
            SettingsGroup(title = "Custom RPCs") {
                ClearAirListRow(title = it, description = "In use", trailing = { ClearAirRadio(selected = true) })
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md)) {
            ClearAirField(
                label = "Add Custom RPC",
                value = draft,
                onValueChange = { draft = it },
                placeholder = "https://",
                keyboard = KeyboardType.Uri,
                monospace = true,
                error = notice,
                inputTag = "custom-rpc",
            )
            Spacer(Modifier.height(ClearAirSpacing.sm))
            ClearAirPrimaryButton(
                text = if (busy) "Checking RPC…" else "Add",
                onClick = { onAddCustom(draft.trim()) },
                enabled = !busy && draft.isNotBlank(),
            )
        }
    }
}
