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

/**
 * The Explore landing (B3): the chains, then the data providers. Each group has a text action to its full list. Each provider row opens its Provider
 * page. The Learn AirVault group is left out until its articles have copy.
 */
@Composable
fun ExploreScreen(
    providers: List<ProviderRow>,
    onChains: () -> Unit,
    onProviders: () -> Unit,
    onProvider: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        ExploreGroup(title = "Chains and L2s", action = "All networks", onAction = onChains) {
            ClearAirRow(title = "Networks", description = "Ethereum, Bitcoin, Solana and Algorand", onClick = onChains)
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
