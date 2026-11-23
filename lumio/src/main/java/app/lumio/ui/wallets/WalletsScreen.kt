package app.lumio.ui.wallets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.Clock
import app.lumio.data.WallClock
import app.lumio.ui.kit.ClearAirAmount
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirListRow
import app.lumio.ui.kit.ClearAirRefreshBox
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.kit.ClearAirWalletEmblem
import app.lumio.ui.kit.LocalFiat
import app.lumio.ui.pair.PairIntro

/** One row of the Accounts list: the wallet's [id] and [name], its [summary] line, and its dollar [usd] total, or null when none of its balances has a price. */
data class WalletRow(val id: String, val name: String, val summary: String, val usd: Double?)

/** The caption under the wallets: what a wallet is, and where its accounts are grouped. */
private const val CAPTION = "Each wallet is one Lensly vault. Open it to see its accounts, grouped by the Lensly app that owns them."

/**
 * The Accounts tab (C1): an eyebrow over the wallets with their total and the freshness line, then a row per wallet, "Pair Another Wallet" as the one
 * text action, and the caption. The root top bar and the tab bar belong to the shell. Pulling down reads the balances again, and the spark draws under
 * the top bar. [notice] names a read that did not answer, and the balances kept from before stay on screen. While [loading], the rows hold their
 * places as skeleton bars.
 */
@Composable
fun WalletsScreen(
    rows: List<WalletRow>,
    totalUsd: Double?,
    showTotal: Boolean,
    refreshing: Boolean,
    updatedAtMs: Long?,
    notice: String?,
    onWallet: (String) -> Unit,
    onPair: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    clock: Clock = WallClock,
    refreshByPull: Boolean = false,
    loading: Boolean = false,
) {
    ClearAirRefreshBox(refreshByPull = refreshByPull, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = ClearAirSpacing.md)) {
            if (loading) {
                // The balances are read on the first frame of the home; until they are, the rows keep their places.
                item(key = "loading") {
                    Column {
                        LoadingRow()
                        LoadingRow()
                    }
                }
            } else if (rows.isEmpty()) {
                item(key = "intro") {
                    PairIntro(onScan = onPair, modifier = Modifier.fillParentMaxHeight())
                }
            } else {
                item(key = "header") {
                    WalletsHeader(count = rows.size, totalUsd = totalUsd, showTotal = showTotal, refreshing = refreshing, updatedAtMs = updatedAtMs, clock = clock)
                }
                notice?.let { message ->
                    item(key = "notice") {
                        WalletNotice(message = message, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter))
                    }
                }
                item(key = "wallets") {
                    Column {
                        rows.forEachIndexed { index, row ->
                            if (index > 0) RowDivider(indent = ROW_DIVIDER_INDENT)
                            ClearAirListRow(
                                title = row.name,
                                description = row.summary,
                                leading = { ClearAirWalletEmblem() },
                                trailing = if (row.usd == null) null else ({ ClearAirAmount(text = LocalFiat.current.amount(row.usd), hidden = !showTotal, style = ClearAirType.rowValue) }),
                                onClick = { onWallet(row.id) },
                            )
                        }
                    }
                }
                item(key = "pair") {
                    ClearAirTextAction(text = "Pair Another Wallet", onClick = onPair, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter))
                }
                item(key = "caption") {
                    Text(
                        text = CAPTION,
                        modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.sm),
                        style = ClearAirType.body,
                        color = LocalClearAirPalette.current.ink2,
                    )
                }
            }
        }
    }
}

/** The eyebrow over the wallets, with their total at its right, and the line that says how fresh the total is. */
@Composable
private fun WalletsHeader(count: Int, totalUsd: Double?, showTotal: Boolean, refreshing: Boolean, updatedAtMs: Long?, clock: Clock) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ClearAirEyebrow(text = "Lensly Wallets · $count", modifier = Modifier.weight(1f))
            totalUsd?.let { usd -> ClearAirAmount(text = LocalFiat.current.amount(usd), hidden = !showTotal, style = ClearAirType.rowValue) }
        }
        FreshnessLine(refreshing = refreshing, updatedAtMs = updatedAtMs, clock = clock)
    }
}
