package app.lumio.ui.asset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.home.Masking
import app.lumio.ui.kit.ClearAirPageTopBar
import app.lumio.ui.kit.ClearAirRow

/**
 * Holdings Breakdown (C5): one coin as the accounts hold it, asset to chain to account. Each chain's header carries its total on the right, and each
 * account row shows its name, then its wallet, index and address, with the amount it holds on the right. Tapping an account opens that account's page.
 * The top bar stays put while the chains scroll. [showBalance] hides the amounts when the owner has the balances hidden.
 */
@Composable
internal fun HoldingsPage(
    title: String,
    symbol: String,
    chains: List<ChainHolding>,
    showBalance: Boolean,
    onAccount: (AccountHolding) -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    Column(modifier = Modifier.fillMaxSize().padding(vertical = ClearAirSpacing.md)) {
        ClearAirPageTopBar(title = title, backLabel = "Asset", onBack = onBack, onLock = onLock)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            chains.forEach { chain ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = chain.chain, style = ClearAirType.rowTitle, color = palette.ink)
                    Text(
                        text = Masking.amount(amountText(chain.total, symbol), hidden = !showBalance),
                        style = ClearAirType.rowValue,
                        color = palette.ink2,
                    )
                }
                chain.accounts.forEach { holding ->
                    ClearAirRow(
                        title = holding.name,
                        description = holding.detail,
                        trailing = {
                            Text(
                                text = Masking.amount(amountText(holding.amount, symbol), hidden = !showBalance),
                                style = ClearAirType.rowValue,
                                color = palette.ink,
                            )
                        },
                        onClick = { onAccount(holding) },
                    )
                }
            }
        }
    }
}
