package app.lumio.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.R
import app.lumio.ui.kit.ClearAirDraggableRows
import app.lumio.ui.kit.ClearAirNote
import app.lumio.ui.kit.ClearAirNumberedRow
import app.lumio.ui.kit.ClearAirSwitchRow

/** One provider as the settings list it. [apiKey] is the key in the vault, or null. [status] is what the scheduler and the settings say now. */
data class ProviderRow(
    val id: String,
    val acceptsKey: Boolean,
    val enabled: Boolean,
    val apiKey: String?,
    val status: ProviderStatus,
)

/**
 * Lumio's data providers: the chain-data providers, the price providers when there are any, then the RPC providers. Each group is numbered, and a row's
 * drag handle changes its place within the group, which applies at once. A row opens its Provider page (B5), where it is turned on or off and its key is
 * set. A group with one provider has a chevron instead of a handle. The screen keeps no state of its own: each change goes to the caller, as [onReorder],
 * [onOpen] or [onPreferRpc]. [onReorder] gets a group's providers, then their new order.
 */
@Composable
fun DataProvidersScreen(
    data: List<ProviderRow>,
    rpc: List<ProviderRow>,
    offline: String?,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onOpen: (id: String) -> Unit,
    onReorder: (group: List<String>, order: List<String>) -> Unit,
    preferRpc: Boolean = false,
    onPreferRpc: (Boolean) -> Unit = {},
    prices: List<ProviderRow> = emptyList(),
    backLabel: String = SETTINGS_BACK,
) {
    SettingsFrame(title = "Data Providers", offline = offline, onBack = onBack, onLock = onLock, backLabel = backLabel) {
        ClearAirNote(
            text = stringResource(R.string.providers_intro),
            modifier = Modifier.padding(top = ClearAirSpacing.md, start = ClearAirSpacing.gutter, end = ClearAirSpacing.gutter),
        )
        ProviderGroup(stringResource(R.string.providers_data_heading), data, onOpen, onReorder)
        if (prices.isNotEmpty()) {
            ProviderGroup(stringResource(R.string.providers_prices_heading), prices, onOpen, onReorder)
        }
        ProviderGroup(stringResource(R.string.providers_rpc_heading), rpc, onOpen, onReorder)
        SettingsGroup(title = stringResource(R.string.providers_privacy_heading)) {
            ClearAirNote(text = stringResource(R.string.providers_privacy_body), modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter))
            ClearAirSwitchRow(
                title = stringResource(R.string.providers_prefer_rpc_title),
                description = stringResource(R.string.providers_prefer_rpc_body),
                checked = preferRpc,
                onCheckedChange = onPreferRpc,
                modifier = Modifier.testTag("prefer-rpc"),
            )
        }
    }
}

@Composable
private fun ProviderGroup(
    heading: String,
    rows: List<ProviderRow>,
    onOpen: (String) -> Unit,
    onReorder: (List<String>, List<String>) -> Unit,
) {
    val group = rows.map { it.id }
    SettingsGroup(title = heading) {
        ClearAirDraggableRows(
            rows = rows,
            key = { it.id },
            onReorder = { order -> onReorder(group, order) },
        ) { index, row, handle ->
            ClearAirNumberedRow(
                number = index + 1,
                title = providerName(row.id),
                description = providerStatusLine(row.status),
                onClick = { onOpen(row.id) },
                dragHandle = if (rows.size > 1) handle else null,
                modifier = Modifier.testTag("provider-${row.id}"),
            )
        }
    }
}
