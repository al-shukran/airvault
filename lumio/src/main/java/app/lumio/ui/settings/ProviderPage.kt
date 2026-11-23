package app.lumio.ui.settings

import androidx.compose.runtime.Composable
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.ui.kit.ClearAirNote
import app.lumio.ui.kit.ClearAirRow
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier

/**
 * The Provider page: one provider's name in the bar, its status word with the line that says why, its factual tags, and the key row. The key row opens
 * the Data Providers page, where the key is typed. [keyed] is whether the provider takes an API key, and [keySet] whether one is stored.
 */
@Composable
fun ProviderPage(
    name: String,
    status: String,
    statusNote: String?,
    tags: List<String>,
    keyed: Boolean,
    keySet: Boolean,
    offline: String?,
    onConfigureKey: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
) {
    SettingsFrame(title = name, offline = offline, onBack = onBack, onLock = onLock) {
        SettingsGroup(title = "Status") {
            ClearAirRow(title = status, description = statusNote)
        }
        tags.forEach { tag ->
            ClearAirRow(title = tag)
        }
        if (keyed) {
            SettingsGroup(title = "Configure API Key") {
                ClearAirRow(title = "API Key", description = if (keySet) "Set" else "Not set", onClick = onConfigureKey)
                ClearAirNote(
                    text = "The API key is optional and stored in the vault.",
                    modifier = Modifier.padding(top = ClearAirSpacing.xs),
                )
            }
        }
    }
}
