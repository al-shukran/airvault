package app.lensly.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.WhitelistEntry
import app.lensly.ui.components.ChainIcon
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.PillChip
import app.lensly.ui.sign.AddressText
import app.lensly.ui.sign.FactRow
import app.lensly.ui.sign.MonoText
import app.lensly.ui.sign.ProxyFacts
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutline
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurface

/**
 * The approved contracts: every whitelist entry of the installed catalog, grouped by network. Each entry shows its contract in
 * full, its function and selector, its approved meaning, and a Proxy chip with the implementation it is bound to. The list only
 * changes through a signed offline update, so an empty list says so and offers Update catalog, the one way to add an entry.
 * [entries] and [networks] are the catalog as read.
 */
@Composable
fun ApprovedContractsScreen(
    entries: List<WhitelistEntry>,
    networks: List<NetworkEntry>,
    onUpdateCatalog: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(title = "Approved contracts", modifier = modifier, onBack = onBack) {
        CatalogIntro(
            text = "Calls on these contracts are signed although Lensly cannot decode them. Each one shows its approved " +
                "meaning before you sign.",
            onLock = onLock,
        )
        if (entries.isEmpty()) {
            Spacer(Modifier.height(LenslySpacing.lg))
            DotHeadline(text = "NO APPROVED CONTRACTS")
            Spacer(Modifier.height(LenslySpacing.sm))
            Text(
                text = "Contracts are added only through a signed offline update (Update catalog). This prevents anyone from " +
                    "approving a contract on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(LenslySpacing.md))
            PillButton(
                text = "Update catalog",
                onClick = onUpdateCatalog,
                modifier = Modifier.fillMaxWidth(),
                accent = true,
            )
        } else {
            entries.groupBy { it.chainId }.forEach { (chainId, group) ->
                Spacer(Modifier.height(LenslySpacing.lg))
                NetworkHeader(network = networks.firstOrNull { it.chainId == chainId }, chainId = chainId)
                Spacer(Modifier.height(LenslySpacing.sm))
                group.forEach { entry ->
                    ApprovalCard(entry)
                    Spacer(Modifier.height(LenslySpacing.sm))
                }
            }
        }
    }
}

/** A network's logo and name, with its chain ID. A chain that is not in the catalog is named by its ID alone. */
@Composable
private fun NetworkHeader(network: NetworkEntry?, chainId: Long) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
    ) {
        ChainIcon(network = network, size = 20.dp)
        Text(
            text = network?.name ?: "Chain $chainId",
            style = MaterialTheme.typography.titleMedium,
            color = LenslyOnSurface,
        )
        PillChip(text = "Chain $chainId")
    }
}

/** One approved call as a card: its function and selector, its contract in full, and its approved meaning. */
@Composable
private fun ApprovalCard(entry: WhitelistEntry) {
    val shape = RoundedCornerShape(LenslyRadius.md)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LenslySurface)
            .border(1.dp, LenslyOutline, shape)
            .padding(horizontal = LenslySpacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
        ) {
            MonoText(text = entry.signature, modifier = Modifier.weight(1f))
            if (entry.proxy != null) PillChip(text = "Proxy")
        }
        FactRow(label = "Contract") {
            AddressText(address = entry.contract)
        }
        FactRow(label = "Selector") {
            MonoText(text = entry.selector)
        }
        FactRow(label = "Approved meaning") {
            Text(
                text = entry.meaning,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        entry.proxy?.let { ProxyFacts(it) }
    }
}
