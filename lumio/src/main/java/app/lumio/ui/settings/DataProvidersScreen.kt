package app.lumio.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.R
import app.lumio.ui.kit.ClearAirNote
import app.lumio.ui.kit.ClearAirSwitchRow
import app.lumio.ui.kit.ClearAirTextAction

/**
 * One provider as the settings list it. [apiKey] is the key in the vault, or null. [status] is what the scheduler and the settings say now. The move
 * flags say whether the provider can go up or down within its group.
 */
data class ProviderRow(
    val id: String,
    val mevProtected: Boolean,
    val acceptsKey: Boolean,
    val enabled: Boolean,
    val apiKey: String?,
    val status: ProviderStatus,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

/**
 * Lumio's data providers: the chain-data providers, the price providers when there are any, then the RPC providers. Each provider is a Clear Air switch
 * row, and its actions sit under it: Details opens its Provider page, where its API key is set, and Move Up and Move Down change its place in its group.
 * The screen keeps no state of its own: each change goes to the caller, as [onEnabled], [onMove] or [onOpen].
 */
@Composable
fun DataProvidersScreen(
    data: List<ProviderRow>,
    rpc: List<ProviderRow>,
    offline: String?,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onOpen: (id: String) -> Unit,
    onEnabled: (id: String, on: Boolean) -> Unit,
    onMove: (id: String, up: Boolean) -> Unit,
    preferRpc: Boolean = false,
    onPreferRpc: (Boolean) -> Unit = {},
    prices: List<ProviderRow> = emptyList(),
) {
    SettingsFrame(title = "Data Providers", offline = offline, onBack = onBack, onLock = onLock) {
        ClearAirNote(
            text = stringResource(R.string.providers_intro),
            modifier = Modifier.padding(top = ClearAirSpacing.md, start = ClearAirSpacing.gutter, end = ClearAirSpacing.gutter),
        )
        ProviderGroup(stringResource(R.string.providers_data_heading), data, onOpen, onEnabled, onMove)
        if (prices.isNotEmpty()) {
            ProviderGroup(stringResource(R.string.providers_prices_heading), prices, onOpen, onEnabled, onMove)
        }
        ProviderGroup(stringResource(R.string.providers_rpc_heading), rpc, onOpen, onEnabled, onMove)
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
    onEnabled: (String, Boolean) -> Unit,
    onMove: (String, Boolean) -> Unit,
) {
    SettingsGroup(title = heading) {
        rows.forEach { row -> ProviderRowItem(row, onOpen, onEnabled, onMove) }
    }
}

@Composable
private fun ProviderRowItem(
    row: ProviderRow,
    onOpen: (String) -> Unit,
    onEnabled: (String, Boolean) -> Unit,
    onMove: (String, Boolean) -> Unit,
) {
    ClearAirSwitchRow(
        title = providerName(row.id),
        description = statusText(row.status),
        checked = row.enabled,
        onCheckedChange = { onEnabled(row.id, it) },
        modifier = Modifier.testTag("enable-${row.id}"),
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(ClearAirSpacing.xs),
    ) {
        ClearAirTextAction(text = "Details", onClick = { onOpen(row.id) }, modifier = Modifier.testTag("open-${row.id}"))
        ClearAirTextAction(
            text = stringResource(R.string.providers_move_up),
            onClick = { onMove(row.id, true) },
            enabled = row.canMoveUp,
            modifier = Modifier.testTag("move-up-${row.id}"),
        )
        ClearAirTextAction(
            text = stringResource(R.string.providers_move_down),
            onClick = { onMove(row.id, false) },
            enabled = row.canMoveDown,
            modifier = Modifier.testTag("move-down-${row.id}"),
        )
    }
}

/** The status word and, when there is one, the line that says why: Working, Rate-Limited or Off, as the Clear Air pages read them. */
private fun statusText(status: ProviderStatus): String = listOfNotNull(providerStatusWord(status), providerStatusNote(status)).joinToString(" · ")
