package app.lumio.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.ui.kit.ClearAirField
import app.lumio.ui.kit.ClearAirNote
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirSwitchRow
import app.lumio.ui.kit.ClearAirTextAction

/**
 * The Provider page (B5) of one provider: its name in the bar, its status word with the line that says why, the switch that turns it on or off, its
 * factual tags, the networks it serves, and its API key. [keyed] is whether the provider takes a key, and [keyRequired] whether it cannot work without
 * one. [savedTail] is the last four characters of the saved key, or null when none is saved, so the key itself never shows again. [keyError] is the
 * message for a key whose save failed. [networks] are the names of the networks it serves, in catalogue order,
 * and the page words them with [networkSummary]. A typed key is saved when the owner leaves its field or presses Done, and [onSaveKey] receives it. Its
 * Remove action passes "". [backLabel] names the screen the back arrow returns to.
 */
@Composable
fun ProviderPage(
    name: String,
    status: String,
    statusNote: String?,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    tags: List<String>,
    networks: List<String>,
    keyed: Boolean,
    keyRequired: Boolean,
    savedTail: String?,
    keyError: String? = null,
    offline: String?,
    onSaveKey: (String) -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    backLabel: String = SETTINGS_BACK,
) {
    SettingsFrame(title = name, offline = offline, onBack = onBack, onLock = onLock, backLabel = backLabel) {
        SettingsGroup(title = "Status") {
            ClearAirRow(title = status, description = statusNote)
            ClearAirSwitchRow(
                title = "Use $name",
                description = "A provider that is off is skipped",
                checked = enabled,
                onCheckedChange = onEnabled,
                modifier = Modifier.testTag("provider-enabled"),
            )
        }
        tags.forEach { tag ->
            ClearAirRow(title = tag)
        }
        if (networks.isNotEmpty()) {
            SettingsGroup(title = "Supported Networks · ${networks.size}") {
                ClearAirNote(text = networkSummary(networks), modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter))
            }
        }
        if (keyed) {
            SettingsGroup(title = "Configure API Key") {
                if (savedTail != null) {
                    ClearAirRow(
                        title = "API Key",
                        description = "Saved · ends in $savedTail",
                        trailing = { ClearAirTextAction(text = "Remove", onClick = { onSaveKey("") }) },
                    )
                } else {
                    ApiKeyEntry(required = keyRequired, error = keyError, onSave = onSaveKey)
                }
                ClearAirNote(
                    text = "Stored in the vault on this phone. Lumio sends it only to $name.",
                    modifier = Modifier.padding(top = ClearAirSpacing.xs),
                )
            }
        }
    }
}

/**
 * The key field, empty until a key is typed. The typed key is saved when the owner leaves the field or presses Done. The field keeps the key
 * until the page replaces the field with the saved key, so a save that fails leaves the key in place with [error] under it.
 */
@Composable
internal fun ApiKeyEntry(required: Boolean, error: String?, onSave: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    ClearAirField(
        label = if (required) "API Key (Required)" else "API Key (Optional)",
        value = draft,
        onValueChange = { draft = it },
        placeholder = "Paste a key for higher limits",
        keyboard = KeyboardType.Password,
        masked = true,
        error = error,
        inputTag = "api-key",
        onCommit = {
            val key = draft.trim()
            if (key.isNotEmpty()) onSave(key)
        },
    )
}
