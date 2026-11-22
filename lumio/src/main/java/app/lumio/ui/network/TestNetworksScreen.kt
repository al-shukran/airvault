package app.lumio.ui.network

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.evm.EvmChain
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirTextAction

/** The chain ID of Monad Testnet, the EVM default: where Send starts. */
private const val MONAD_TESTNET = 10143L

/**
 * The test networks page: the EVM test networks, each with a switch, and the test networks that have no switch, since each of those chains has one
 * test network. [chains] are the EVM test networks the build lists, [enabled] are the ones that are on, and a switch calls [onEnabled] at once.
 * [notice] says why a change was refused.
 */
@Composable
fun TestNetworksScreen(
    chains: List<EvmChain>,
    enabled: Set<Long>,
    notice: String?,
    onEnabled: (Long, Boolean) -> Unit,
    onAddCustom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalClearAirPalette.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md),
    ) {
        Text(
            text = "When Lumio is on Testnet, it reads and sends on these networks.",
            style = ClearAirType.body,
            color = palette.ink2,
        )
        notice?.let { Text(text = it, style = ClearAirType.body, color = palette.rose) }
        ClearAirEyebrow("Ethereum App")
        chains.forEach { chain ->
            ClearAirRow(
                title = chain.name,
                description = if (chain.chainId == MONAD_TESTNET) {
                    "Default · Chain ID ${chain.chainId} · where Send starts"
                } else {
                    "Chain ID ${chain.chainId}"
                },
                trailing = {
                    Switch(checked = chain.chainId in enabled, onCheckedChange = { onEnabled(chain.chainId, it) })
                },
            )
        }
        ClearAirTextAction(text = "Add Custom Test Network", onClick = onAddCustom)
        ClearAirEyebrow("Bitcoin App")
        ClearAirRow(title = "Bitcoin Testnet4", description = "The only Bitcoin test network Lumio reads")
        ClearAirEyebrow("Solana App")
        ClearAirRow(title = "Solana Devnet", description = "The only Solana test cluster Lumio reads")
        ClearAirEyebrow("Algorand App")
        ClearAirRow(title = "Algorand Testnet", description = "Algorand's public test network")
    }
}
