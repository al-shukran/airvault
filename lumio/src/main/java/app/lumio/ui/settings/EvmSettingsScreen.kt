package app.lumio.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettings
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirStatusMark
import app.lumio.ui.kit.ClearAirSwitchRow
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.kit.ClearAirTone

/**
 * The Ethereum section of the Networks page: how many addresses each wallet shows, then each chain as a switch row. Under a chain's switch sit its
 * actions: Network opens the page that sets its RPC address, Tokens opens its tokens, and Remove drops a chain the owner added. [onEnabled] and
 * [onAddressCount] report a change to the caller, which writes it. [notice] shows a refusal that is not tied to one row. [busy] holds every change
 * until the last one has finished. [onAddNetwork] opens the page that adds a custom network.
 */
@Composable
fun EvmSettingsSection(
    settings: EvmSettings,
    chains: List<EvmChain>,
    busy: Boolean,
    notice: String?,
    onAddressCount: (Int) -> Unit,
    onEnabled: (chainId: Long, on: Boolean) -> Unit,
    onRemoveCustom: (chainId: Long) -> Unit,
    onNetwork: (chainId: Long) -> Unit,
    onAddNetwork: () -> Unit,
    onTokens: (chainId: Long) -> Unit,
) {
    notice?.let {
        ClearAirStatusMark(glyph = "!", text = it, tone = ClearAirTone.Failed, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter))
    }
    SettingsGroup(title = "Addresses per Wallet") {
        AddressCountRow(count = settings.addressCount, busy = busy, onCount = onAddressCount)
    }
    SettingsGroup(title = "Chains") {
        chains.forEach { chain ->
            ChainRow(
                chain = chain,
                settings = settings,
                busy = busy,
                onEnabled = { on -> onEnabled(chain.chainId, on) },
                onNetwork = { onNetwork(chain.chainId) },
                onTokens = { onTokens(chain.chainId) },
                onRemove = { onRemoveCustom(chain.chainId) },
            )
        }
        ClearAirRow(
            title = "Add Custom Network",
            description = "An EVM network the registry does not list",
            onClick = if (busy) null else onAddNetwork,
        )
    }
}

/** How many addresses each wallet shows, with one step down and one step up. The steps stop at the limits. */
@Composable
private fun AddressCountRow(count: Int, busy: Boolean, onCount: (Int) -> Unit) {
    ClearAirRow(
        title = "$count shown",
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(ClearAirSpacing.xs)) {
                ClearAirTextAction(text = "−", onClick = { onCount(count - 1) }, enabled = !busy && count > 1)
                ClearAirTextAction(text = "+", onClick = { onCount(count + 1) }, enabled = !busy && count < EvmSettings.MAX_ADDRESS_COUNT)
            }
        },
    )
}

/**
 * One chain: a switch that turns its reads on or off, its ID and whether its RPC address is the owner's, and the actions beneath it. Only a chain
 * the owner added can be removed.
 */
@Composable
private fun ChainRow(
    chain: EvmChain,
    settings: EvmSettings,
    busy: Boolean,
    onEnabled: (Boolean) -> Unit,
    onNetwork: () -> Unit,
    onTokens: () -> Unit,
    onRemove: () -> Unit,
) {
    val custom = settings.custom.any { it.chainId == chain.chainId }
    val rpc = if (settings.rpc.containsKey(chain.chainId)) "custom RPC in use" else "built-in RPC"
    val testnet = if (chain.testnet) " · Testnet" else ""
    ClearAirSwitchRow(
        title = chain.name,
        description = "ID ${chain.chainId} · $rpc$testnet",
        checked = chain.chainId in settings.enabled,
        onCheckedChange = onEnabled,
        enabled = !busy,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(ClearAirSpacing.xs),
    ) {
        ClearAirTextAction(text = "Network", onClick = onNetwork, enabled = !busy)
        ClearAirTextAction(text = "Tokens", onClick = onTokens, enabled = !busy)
        if (custom) ClearAirTextAction(text = "Remove", onClick = onRemove, enabled = !busy)
    }
}
