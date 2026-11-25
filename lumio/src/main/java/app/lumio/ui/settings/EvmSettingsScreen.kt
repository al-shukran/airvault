package app.lumio.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.core.registry.NetworkEntry
import app.lensly.ui.components.ChainIcon
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettings
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirMonogram
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirStatusMark
import app.lumio.ui.kit.ClearAirSwitch
import app.lumio.ui.kit.ClearAirTag
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.kit.ClearAirTone
import app.lumio.ui.wallets.ROW_DIVIDER_INDENT

/** The Networks page: the EVM networks with their switches, then the Bitcoin servers. [content] draws them. */
@Composable
fun NetworksSettingsPage(
    offline: String?,
    onBack: () -> Unit,
    onLock: () -> Unit,
    backLabel: String = SETTINGS_BACK,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsPageScreen(SettingsPage.NETWORKS, offline, onBack, onLock, backLabel = backLabel, content = content)
}

/**
 * The networks of the Networks page: how many EVM networks there are, a search field, then the mainnets and the testnets, each with a logo, its ID, whether
 * its RPC is the owner's and a switch. The switch turns a network on or off, and the rest of its row opens its Network page ([onNetwork]), where its RPCs, its
 * tokens and, for a network the owner added, its removal are. Add Custom Network opens its form ([onAddNetwork]). Below them sit how many addresses each wallet shows,
 * and the Bitcoin servers' page ([onBitcoin]). [logos] gives the registry entry of a chain by its ID, and a chain without one shows its first letter.
 * [onEnabled] and [onAddressCount] report a change to the caller, which writes it. [notice] shows a refusal that is not tied to one row. [busy] holds every
 * change until the last one has finished.
 */
@Composable
fun EvmSettingsSection(
    settings: EvmSettings,
    chains: List<EvmChain>,
    logos: Map<Long, NetworkEntry>,
    busy: Boolean,
    notice: String?,
    onAddressCount: (Int) -> Unit,
    onEnabled: (chainId: Long, on: Boolean) -> Unit,
    onNetwork: (chainId: Long) -> Unit,
    onAddNetwork: () -> Unit,
    onBitcoin: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = chains.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.chainId.toString().contains(query.trim()) }
    val mainnets = chains.filterNot { it.testnet }
    val testnets = chains.filter { it.testnet }
    SettingsExplainer(
        text = "${networkCount(mainnets.size, "EVM network")} · ${networkCount(testnets.size, "test network")}",
        modifier = Modifier.padding(top = 2.dp),
    )
    notice?.let {
        ClearAirStatusMark(glyph = "!", text = it, tone = ClearAirTone.Failed, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter))
    }
    SearchField(query = query, onQuery = { query = it })
    ChainGroup("Mainnet", 16.dp, shown.filterNot { it.testnet }, mainnets, settings, logos, busy, onEnabled, onNetwork)
    ChainGroup("Testnet", 18.dp, shown.filter { it.testnet }, testnets, settings, logos, busy, onEnabled, onNetwork)
    AddNetworkAction(enabled = !busy, onClick = onAddNetwork)
    SettingsGroup(title = "Addresses per Wallet") {
        AddressCountRow(count = settings.addressCount, busy = busy, onCount = onAddressCount)
    }
    SettingsGroup(title = "Bitcoin") {
        ClearAirRow(title = "Bitcoin Servers", description = "Mainnet, Testnet4 and the Ordinals indexer", onClick = onBitcoin)
    }
}

/** "55 EVM networks", or "1 EVM network" for one. */
private fun networkCount(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

/** The search field of the page: a raised capsule with the search glyph, which narrows the networks to those whose name or chain ID holds the text. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ClearAirSpacing.md, end = ClearAirSpacing.md, top = 10.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(palette.raised)
            .padding(horizontal = ClearAirSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClearAirIcon(glyph = ClearAirGlyph.SEARCH, color = palette.ink2, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(text = "Search networks", style = ClearAirType.body, color = palette.ink2, maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = ClearAirType.body.copy(color = palette.ink),
                cursorBrush = SolidColor(palette.ink),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One side's networks under its line, which counts how many of [all] are on. A search that leaves none of [rows] hides the group. */
@Composable
private fun ChainGroup(
    title: String,
    top: androidx.compose.ui.unit.Dp,
    rows: List<EvmChain>,
    all: List<EvmChain>,
    settings: EvmSettings,
    logos: Map<Long, NetworkEntry>,
    busy: Boolean,
    onEnabled: (Long, Boolean) -> Unit,
    onNetwork: (Long) -> Unit,
) {
    if (rows.isEmpty()) return
    SettingsValueLine(title = title, value = "${all.count { it.chainId in settings.enabled }} on", top = top, valueMono = false)
    rows.forEachIndexed { index, chain ->
        if (index > 0) SettingsDivider(start = ROW_DIVIDER_INDENT)
        ChainRow(
            chain = chain,
            settings = settings,
            logo = logos[chain.chainId],
            busy = busy,
            onEnabled = { on -> onEnabled(chain.chainId, on) },
            onNetwork = { onNetwork(chain.chainId) },
        )
    }
}

/** The Add Custom Network action: a plus and its words, as the one secondary action under the lists. */
@Composable
private fun AddNetworkAction(enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier
            .padding(horizontal = ClearAirSpacing.gutter)
            .defaultMinSize(minHeight = ClearAirSpacing.touchTarget)
            .lumioPress(scale = ClearAirMotion.PRESS_SCALE, enabled = enabled, releaseSpring = ClearAirMotion.press, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClearAirIcon(glyph = ClearAirGlyph.PLUS, color = palette.ink, size = 18.dp)
        Spacer(Modifier.width(6.dp))
        Text(text = "Add Custom Network", style = ClearAirType.rowTitle, color = palette.ink, maxLines = 1)
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
 * One network: its logo, its name with DEFAULT when it is where Send starts, and under it its ID and whether its RPC address is the owner's. The row opens the
 * network, and the switch at its end turns its reads on or off.
 */
@Composable
private fun ChainRow(
    chain: EvmChain,
    settings: EvmSettings,
    logo: NetworkEntry?,
    busy: Boolean,
    onEnabled: (Boolean) -> Unit,
    onNetwork: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    val on = chain.chainId in settings.enabled
    val default = chain.testnet && chain.chainId == EvmCatalog.primaryChainId(testnet = true)
    val line = when {
        !on -> "off"
        default -> "where Send starts"
        settings.activeRpc(chain.chainId) != null -> "custom RPC in use"
        else -> "built-in RPC"
    }
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 62.dp).padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .lumioPress(scale = ClearAirMotion.PRESS_SCALE, enabled = !busy, releaseSpring = ClearAirMotion.press, onClick = onNetwork),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(palette.raised), contentAlignment = Alignment.Center) {
                if (logo != null) {
                    ChainIcon(network = logo, size = 21.dp)
                } else {
                    ClearAirMonogram(text = chain.name.firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty(), tile = 36.dp)
                }
            }
            Spacer(Modifier.width(ClearAirSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = chain.name,
                        modifier = Modifier.weight(1f, fill = false),
                        style = ClearAirType.rowTitle,
                        color = palette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (default) ClearAirTag(text = "DEFAULT", modifier = Modifier.padding(start = 6.dp))
                }
                Text(text = "ID ${chain.chainId} · $line", style = ClearAirType.caption, color = palette.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(ClearAirSpacing.sm))
        // The 46 x 28 dp track sits at the end of a 48 dp box, so the touch target is 48 dp and the track stays where it was drawn. TalkBack names the network.
        Box(
            modifier = Modifier
                .sizeIn(minWidth = ClearAirSpacing.touchTarget, minHeight = ClearAirSpacing.touchTarget)
                .semantics { contentDescription = "${chain.name} network" }
                .toggleable(value = on, enabled = !busy, role = Role.Switch, onValueChange = onEnabled),
            contentAlignment = Alignment.CenterEnd,
        ) {
            ClearAirSwitch(checked = on, enabled = !busy)
        }
    }
}
