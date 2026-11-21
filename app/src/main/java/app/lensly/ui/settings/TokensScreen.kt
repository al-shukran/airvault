package app.lensly.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.ChainIcon
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.TokenIcon
import app.lensly.ui.components.settingsIslandItems
import app.lensly.ui.theme.LenslySpacing

/** One line of the Tokens island: a network's header, or one token under it. [key] names the line for the list. */
private sealed interface TokenLine {
    val key: String

    data class Header(val chainId: Long, val network: NetworkEntry?, val count: Int) : TokenLine {
        override val key: String = "chain-$chainId"
    }

    data class Token(val token: TokenEntry, val network: NetworkEntry?) : TokenLine {
        override val key: String = "${token.chainId}-${token.contract}"
    }
}

/**
 * The tokens Lensly can sign for, in one island, grouped under their network. A row of chain logos above the island picks one network,
 * or all of them. Each group opens with a header line: the network's logo, its name and its token count. Each token shows its logo,
 * symbol, name, decimals and full contract address. The island is a lazy list, so only the tokens on screen are composed.
 * [networks] and [tokens] are the catalog as read.
 */
@Composable
fun TokensScreen(
    networks: List<NetworkEntry>,
    tokens: List<TokenEntry>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chainFilter by remember { mutableStateOf<Long?>(null) }
    val networkById = remember(networks) { networks.associateBy { it.chainId } }
    val groups = remember(tokens) { tokens.groupBy { it.chainId } }
    val chains = networks.filter { it.chainId in groups }
    val lines = remember(groups, chainFilter, networkById) {
        groups.entries
            .filter { chainFilter == null || it.key == chainFilter }
            .flatMap { (chainId, group) ->
                val network = networkById[chainId]
                listOf<TokenLine>(TokenLine.Header(chainId, network, group.size)) + group.map { TokenLine.Token(it, network) }
            }
    }

    LenslyScreen(title = "Tokens", modifier = modifier, onBack = onBack, scrollable = false) {
        CatalogIntro(
            text = "Lensly can sign for these tokens offline. Others are signed with a warning.",
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = LenslySpacing.md),
            horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
        ) {
            FilterChip(
                selected = chainFilter == null,
                onClick = { chainFilter = null },
                label = { Text("All") },
            )
            for (network in chains) {
                FilterChip(
                    selected = chainFilter == network.chainId,
                    onClick = { chainFilter = network.chainId },
                    label = {
                        ChainIcon(
                            network = network,
                            size = CHIP_ICON_SIZE,
                            modifier = Modifier.semantics { contentDescription = network.name },
                        )
                    },
                )
            }
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = LenslySpacing.md)) {
            settingsIslandItems(title = "Tokens", rows = lines, key = { it.key }) { _, line ->
                when (line) {
                    is TokenLine.Header -> GroupHeader(chainId = line.chainId, network = line.network, count = line.count)
                    is TokenLine.Token -> TokenRow(token = line.token, network = line.network)
                }
            }
        }
    }
}

/** The icon of a chain in the filter row. */
private val CHIP_ICON_SIZE = 20.dp

/** The logo at the start of a network's group header. */
private val HEADER_ICON_SIZE = 24.dp

/** The logo at the start of a token row. */
private val ROW_ICON_SIZE = 40.dp

/**
 * The height of a token row. Every row keeps it, so the list reads as one column whatever the length of a name or a
 * contract address. The name is the only line that may be cut short; the symbol and the address are always shown in full.
 */
private val ROW_MIN_HEIGHT = 172.dp

/** The header of one network's group, which sits in the island above that group's tokens. */
@Composable
private fun GroupHeader(chainId: Long, network: NetworkEntry?, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChainIcon(network = network, size = HEADER_ICON_SIZE)
        Spacer(Modifier.width(LenslySpacing.sm))
        Text(
            text = network?.name ?: "Chain $chainId",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (count == 1) "1 token" else "$count tokens",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One token: its logo, symbol, name and decimals, then the full contract address in groups of four. */
@Composable
private fun TokenRow(token: TokenEntry, network: NetworkEntry?) {
    Column(modifier = Modifier.fillMaxWidth().heightIn(min = ROW_MIN_HEIGHT).padding(vertical = LenslySpacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TokenIcon(token = token, size = ROW_ICON_SIZE, chain = network)
            Spacer(Modifier.width(LenslySpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = token.symbol,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = token.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${token.decimals} decimals",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AddressText(
            address = token.contract,
            icon = false,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = LenslySpacing.sm),
        )
    }
}
