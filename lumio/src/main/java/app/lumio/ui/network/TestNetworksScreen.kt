package app.lumio.ui.network

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.evm.EvmChain
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirSwitch
import app.lumio.ui.kit.ClearAirTag

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
            val default = chain.chainId == MONAD_TESTNET
            SwitchRow(
                title = chain.name,
                description = if (default) "Chain ID ${chain.chainId} · where Send starts" else "Chain ID ${chain.chainId}",
                tag = if (default) "DEFAULT" else null,
                checked = chain.chainId in enabled,
                onCheckedChange = { onEnabled(chain.chainId, it) },
            )
        }
        ClearAirRow(
            title = "Add Custom Test Network",
            description = "Any EVM chain, by chain ID and RPC",
            leading = { GlyphDisc(ClearAirGlyph.PLUS, glyphSize = 19.dp) },
            onClick = onAddCustom,
        )
        ClearAirEyebrow("Bitcoin App")
        ClearAirRow(title = "Bitcoin Testnet4", description = "The only Bitcoin test network Lumio reads")
        ClearAirEyebrow("Solana App")
        ClearAirRow(title = "Solana Devnet", description = "The only Solana test cluster Lumio reads")
        ClearAirEyebrow("Algorand App")
        ClearAirRow(title = "Algorand Testnet", description = "Algorand's public test network")
    }
}

/**
 * A test network with a switch. The whole row is the switch, as a settings row is, and a [tag] such as DEFAULT sits after the name.
 */
@Composable
private fun SwitchRow(title: String, description: String, tag: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 62.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = ClearAirSpacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = title, style = ClearAirType.rowTitle, color = palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (tag != null) {
                    Spacer(Modifier.width(ClearAirSpacing.xs))
                    ClearAirTag(text = tag)
                }
            }
            Text(text = description, style = ClearAirType.body, color = palette.ink2)
        }
        Spacer(Modifier.width(ClearAirSpacing.sm))
        ClearAirSwitch(checked = checked)
    }
}
