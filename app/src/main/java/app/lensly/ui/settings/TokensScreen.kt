package app.lensly.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import app.lensly.ui.components.ChainIcon
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.TokenIcon
import app.lensly.ui.home.groupedAddress
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyMuted
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.SpaceMonoFamily

/**
 * The tokens Lensly can sign for, grouped under their network. A row of chain logos above the list picks one network, or all of
 * them. Each group opens with a sticky header: the network's logo, its name and its token count. Each token shows its logo,
 * symbol, name, decimals and full contract address. [networks] and [tokens] are the catalog as read.
 */
@Composable
fun TokensScreen(
    networks: List<NetworkEntry>,
    tokens: List<TokenEntry>,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chainFilter by remember { mutableStateOf<Long?>(null) }
    val networkById = remember(networks) { networks.associateBy { it.chainId } }
    val groups = remember(tokens) { tokens.groupBy { it.chainId } }
    val chains = networks.filter { it.chainId in groups }

    LenslyScreen(title = "Tokens", modifier = modifier, onBack = onBack, scrollable = false) {
        CatalogIntro(
            text = "Lensly can sign for these tokens offline. Others are signed with a warning.",
            onLock = onLock,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.md),
            horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
        ) {
            item(key = "all") {
                FilterChip(
                    selected = chainFilter == null,
                    onClick = { chainFilter = null },
                    label = { Text("All") },
                )
            }
            items(chains, key = { it.chainId }) { network ->
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
            for ((chainId, group) in groups) {
                if (chainFilter != null && chainFilter != chainId) continue
                val network = networkById[chainId]
                stickyHeader(key = "chain-$chainId") {
                    GroupHeader(chainId = chainId, network = network, count = group.size)
                }
                items(group, key = { "${it.chainId}-${it.contract}" }) { token ->
                    TokenRow(token = token, network = network, modifier = Modifier.animateItem())
                }
            }
        }
    }
}

/** The icon of a chain in the filter row. */
private val CHIP_ICON_SIZE = 20.dp

/** The header of one network's group. It stays on top while the group's tokens scroll under it. */
@Composable
private fun GroupHeader(chainId: Long, network: NetworkEntry?, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LenslyBlack)
            .padding(vertical = LenslySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChainIcon(network = network, size = 24.dp)
        Spacer(Modifier.width(LenslySpacing.sm))
        Text(
            text = network?.name ?: "Chain $chainId",
            style = MaterialTheme.typography.titleSmall,
            color = LenslyOnSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (count == 1) "1 token" else "$count tokens",
            style = MaterialTheme.typography.labelMedium,
            color = LenslyMuted,
        )
    }
}

/** One token: its logo, symbol, name and decimals, then the full contract address in groups of four. */
@Composable
private fun TokenRow(token: TokenEntry, network: NetworkEntry?, modifier: Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = LenslySpacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TokenIcon(token = token, size = 40.dp, chain = network)
            Spacer(Modifier.width(LenslySpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = token.symbol,
                    style = MaterialTheme.typography.titleMedium,
                    color = LenslyOnSurface,
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
                    color = LenslyMuted,
                )
            }
        }
        Text(
            text = groupedAddress(token.contract),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceMonoFamily),
            color = LenslyOnSurface,
            modifier = Modifier.padding(top = LenslySpacing.sm),
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = LenslySpacing.md),
        )
    }
}
