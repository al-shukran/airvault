package app.lumio.ui.explore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lensly.app.familyLabel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.NetworkKind
import app.lensly.core.registry.Registry
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.data.net.Networks
import app.lumio.network.NetworkMode
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.settings.ProviderRow
import app.lumio.ui.settings.providerName
import app.lumio.ui.settings.providerStatusNote
import app.lumio.ui.settings.providerStatusWord
import app.lumio.wallets.WalletAccount
import app.lumio.wallets.WalletAccounts

/** A row of the chains group: a network's [title] and its [description], such as "Layer 1 · 4 of your accounts" or "L2 on Ethereum · ID 8453". */
data class ChainRow(val title: String, val description: String)

/**
 * The rows of the chains group on [mode]'s side, in family order. Each Ethereum network that is featured or holds one of [accounts] has a row, in
 * chain-ID order. Bitcoin, Solana and Algorand have a row for each network that holds accounts, as they are Layer 1 chains the registry does not list.
 */
internal fun chainRows(registry: Registry, mode: NetworkMode, accounts: List<WalletAccount>): List<ChainRow> {
    val onChain = accounts.groupingBy { it.id.chainId }.eachCount()
    return WalletAccounts.FAMILIES.flatMap { family ->
        if (family == Networks.EIP155) {
            registry.networks()
                .filter { mode.holds(it.testnet) && (it.featured || Networks.evm(it.chainId) in onChain) }
                .map { network -> evmRow(registry, network, onChain[Networks.evm(network.chainId)] ?: 0) }
        } else {
            onChain.filterKeys { it.namespace == family }
                .map { (_, count) -> ChainRow(familyLabel(family), "Layer 1 · $count of your accounts") }
        }
    }
}

/** The row of [network], which reads its count of [accounts], or its chain ID when no account sits on it. */
private fun evmRow(registry: Registry, network: NetworkEntry, accounts: Int): ChainRow {
    val kind = kindOf(registry, network)
    val line = if (accounts > 0) "$kind · $accounts of your accounts" else "$kind · ID ${network.chainId}"
    return ChainRow(network.name, line)
}

/** "Layer 1", or "L2 on" the name of the parent chain, such as "L2 on Ethereum". */
private fun kindOf(registry: Registry, network: NetworkEntry): String = when (network.kind) {
    NetworkKind.LAYER_1 -> "Layer 1"
    NetworkKind.LAYER_2 -> "L2 on ${checkNotNull(registry.network(checkNotNull(network.parentChainId))).name}"
}

/**
 * The Explore landing (B3): the chains, then the data providers. The chains group names each network the owner's accounts sit on, and each
 * featured network, with its kind and its count of accounts or its chain ID. Its action reads "All" and the count of [networks] on the side. Each
 * group has a text action to its full list. Each provider row opens its Provider page. The Learn AirVault group is left out until its articles have copy.
 */
@Composable
fun ExploreScreen(
    chains: List<ChainRow>,
    networks: Int,
    providers: List<ProviderRow>,
    onChains: () -> Unit,
    onProviders: () -> Unit,
    onProvider: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        ExploreGroup(title = "Chains and L2s", action = "All $networks", onAction = onChains) {
            chains.forEach { chain ->
                ClearAirRow(title = chain.title, description = chain.description, onClick = onChains)
            }
        }
        ExploreGroup(title = "Data Providers", action = "All ${providers.size}", onAction = onProviders) {
            providers.forEach { row ->
                ClearAirRow(
                    title = providerName(row.id),
                    description = listOfNotNull(providerStatusWord(row.status), providerStatusNote(row.status)).joinToString(" · "),
                    onClick = { onProvider(row.id) },
                )
            }
        }
    }
}

/** A group of the landing: its eyebrow with a text action to the full list, then its rows. */
@Composable
private fun ExploreGroup(title: String, action: String, onAction: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = ClearAirSpacing.lg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClearAirEyebrow(text = title, modifier = Modifier.weight(1f))
            ClearAirTextAction(text = action, onClick = onAction)
        }
        content()
    }
}
