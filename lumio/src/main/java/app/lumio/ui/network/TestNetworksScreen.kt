package app.lumio.ui.network

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.evm.EvmChain
import app.lumio.ui.home.AssetDisc
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirGlyphDisc
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirSwitchRow

/** The chain ID of Monad Testnet, the EVM default: where Send starts. */
private const val MONAD_TESTNET = 10143L

/**
 * The test networks page: the EVM test networks, each with a switch, and the test networks that have no switch, since each of those chains has one
 * test network. [chains] are the EVM test networks the build lists, [enabled] are the ones that are on, and a switch calls [onEnabled] at once.
 * [notice] says why a change was refused, and [iconOf] gives the logo of an EVM network.
 */
@Composable
fun TestNetworksScreen(
    chains: List<EvmChain>,
    enabled: Set<Long>,
    notice: String?,
    onEnabled: (Long, Boolean) -> Unit,
    onAddCustom: () -> Unit,
    iconOf: (EvmChain) -> AssetIconSpec,
    modifier: Modifier = Modifier,
) {
    val palette = LocalClearAirPalette.current
    val gutter = Modifier.padding(horizontal = ClearAirSpacing.gutter)
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = ClearAirSpacing.md)) {
        Text(
            text = "When Lumio is on Testnet, it reads and sends on these networks.",
            modifier = gutter,
            style = ClearAirType.body,
            color = palette.ink2,
        )
        notice?.let { Text(text = it, modifier = gutter, style = ClearAirType.body, color = palette.rose) }
        Section("Ethereum App")
        chains.forEach { chain ->
            val default = chain.chainId == MONAD_TESTNET
            ClearAirSwitchRow(
                title = chain.name,
                description = if (default) "Chain ID ${chain.chainId} · where Send starts" else "Chain ID ${chain.chainId}",
                leading = { AssetDisc(iconOf(chain)) },
                tag = if (default) "DEFAULT" else null,
                checked = chain.chainId in enabled,
                onCheckedChange = { onEnabled(chain.chainId, it) },
                divider = chain != chains.first(),
            )
        }
        ClearAirRow(
            title = "Add Custom Test Network",
            description = "Any EVM chain, by chain ID and RPC",
            leading = { ClearAirGlyphDisc(ClearAirGlyph.PLUS, size = 19.dp) },
            onClick = onAddCustom,
            divider = chains.isNotEmpty(),
        )
        Section("Bitcoin App")
        ClearAirRow(title = "Bitcoin Testnet4", description = "The only Bitcoin test network Lumio reads", leading = { AssetDisc(AssetIconSpec.Bitcoin) })
        Section("Solana App")
        ClearAirRow(title = "Solana Devnet", description = "The only Solana test cluster Lumio reads", leading = { AssetDisc(AssetIconSpec.Solana) })
        Section("Algorand App")
        ClearAirRow(title = "Algorand Testnet", description = "Algorand's public test network", leading = { AssetDisc(AssetIconSpec.Algorand) })
    }
}

/** The eyebrow of a group, 24 dp under the group before it. */
@Composable
private fun Section(title: String) {
    ClearAirEyebrow(title, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter), top = ClearAirSpacing.lg, bottom = ClearAirSpacing.xxs)
}
