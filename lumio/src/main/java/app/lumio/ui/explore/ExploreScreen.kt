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
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.settings.ProviderRow
import app.lumio.ui.settings.providerName
import app.lumio.ui.settings.providerStatusNote
import app.lumio.ui.settings.providerStatusWord
import app.lumio.ui.wallets.accountsByChain
import app.lumio.wallets.WalletAccount
import app.lumio.wallets.WalletAccounts

/** A chain family that the owner's accounts sit on: its [name], such as "Ethereum", and how many of the owner's accounts it holds. */
data class ChainRow(val name: String, val accounts: Int)

/** The chain families [accounts] sit on, in family order, each with its count of accounts. A family with no account has no row. */
internal fun chainRows(accounts: List<WalletAccount>): List<ChainRow> =
    accountsByChain(accounts).map { (family, group) -> ChainRow(name = WalletAccounts.familyLabel(family), accounts = group.size) }

/**
 * The Explore landing (B3): the chains, then the data providers. The chains group names each chain family the owner's accounts sit on, with its count
 * of accounts, and its action reads "All" and the count of [networks] on the side. Each group has a text action to its full list. Each provider row
 * opens its Provider page. The Learn AirVault group is left out until its articles have copy.
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
                ClearAirRow(title = chain.name, description = "${chain.accounts} of your accounts", onClick = onChains)
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
