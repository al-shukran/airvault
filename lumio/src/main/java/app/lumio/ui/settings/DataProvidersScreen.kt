package app.lumio.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.R
import app.lumio.ui.kit.ClearAirDraggableRows
import app.lumio.ui.kit.ClearAirNote
import app.lumio.ui.kit.ClearAirSwitchRow

/** One provider as the settings list it. [apiKey] is the key in the vault, or null. [status] is what the scheduler and the settings say now. */
data class ProviderRow(
    val id: String,
    val acceptsKey: Boolean,
    val enabled: Boolean,
    val apiKey: String?,
    val status: ProviderStatus,
)

/** The data providers that read Bitcoin. Every other data provider reads EVM networks. */
private val BITCOIN_PROVIDERS = setOf("mempool", "blockstream")

/**
 * Lumio's data providers, grouped by what they serve: the data providers of the EVM networks and of Bitcoin, the RPC providers, and the price providers when
 * there are any. Each group is numbered, and a row's drag handle changes its place within the group, which applies at once. A row opens its Provider page
 * (B5), where it is turned on or off and its key is set. A group with one provider has a chevron instead of a handle. The screen keeps no state of its own:
 * each change goes to the caller, as [onReorder], [onOpen] or [onPreferRpc]. [onReorder] gets all the data providers, then their new order, because the EVM
 * and Bitcoin groups are one ordering that the screen shows in two parts.
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
    val dataIds = data.map { it.id }
    // A part's new order takes the places that the part holds among all the data providers, so the other part keeps its own.
    fun reorderPart(part: List<ProviderRow>, order: List<String>) {
        val partIds = part.map { it.id }.toSet()
        val slots = dataIds.indices.filter { dataIds[it] in partIds }
        val next = dataIds.toMutableList()
        slots.zip(order).forEach { (slot, id) -> next[slot] = id }
        onReorder(dataIds, next)
    }
    val evm = data.filter { it.id !in BITCOIN_PROVIDERS }
    val bitcoin = data.filter { it.id in BITCOIN_PROVIDERS }
    SettingsFrame(title = "Data Providers", offline = offline, onBack = onBack, onLock = onLock, backLabel = backLabel) {
        SettingsExplainer(text = stringResource(R.string.providers_intro), modifier = Modifier.padding(top = 2.dp))
        ProviderGroup(stringResource(R.string.providers_evm_heading), 12.dp, evm, onOpen) { reorderPart(evm, it) }
        ProviderGroup(stringResource(R.string.providers_bitcoin_heading), 18.dp, bitcoin, onOpen) { reorderPart(bitcoin, it) }
        ProviderGroup(stringResource(R.string.providers_rpc_heading), 18.dp, rpc, onOpen) { order -> onReorder(rpc.map { it.id }, order) }
        if (prices.isNotEmpty()) {
            ProviderGroup(stringResource(R.string.providers_prices_heading), 18.dp, prices, onOpen) { order -> onReorder(prices.map { it.id }, order) }
        }
        SettingsGroup(title = stringResource(R.string.providers_privacy_heading)) {
            ClearAirNote(text = stringResource(R.string.providers_privacy_body))
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
    top: androidx.compose.ui.unit.Dp,
    rows: List<ProviderRow>,
    onOpen: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    if (rows.isEmpty()) return
    SettingsHeading(title = heading, top = top)
    val inUse = rows.indexOfFirst { it.status.working() }
    ClearAirDraggableRows(rows = rows, key = { it.id }, onReorder = onReorder, rowModifier = { Modifier.testTag("provider-${it.id}") }) { index, row, handle ->
        Column {
            if (index > 0) SettingsDivider()
            ProviderOrderRow(
                number = index + 1,
                title = providerName(row.id),
                status = row.status,
                detail = providerStatusDetail(row.status, inUse = index == inUse),
                onClick = { onOpen(row.id) },
                dragHandle = if (rows.size > 1) handle else null,
            )
        }
    }
}
