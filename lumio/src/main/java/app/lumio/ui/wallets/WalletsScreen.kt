package app.lumio.ui.wallets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.DetailTopBar
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.IslandDivider
import app.lensly.ui.components.LumioIsland
import app.lensly.ui.components.LumioRow
import app.lensly.ui.components.LumioTextAction
import app.lensly.ui.theme.LenslySpacing
import app.lumio.data.Clock
import app.lumio.data.WallClock
import app.lumio.home.HomeFormat
import app.lumio.home.Masking
import app.lumio.ui.kit.GlowBackdrop
import app.lumio.ui.kit.WalletOrb
import app.lumio.ui.pair.PairIntro
import app.lumio.ui.theme.LumioGutter

/**
 * One row of the Wallets list: the wallet's [id] and [name], the [fingerprint] its orb is drawn from, its [summary] line, and its dollar [usd] total, or
 * null when none of its balances has a price.
 */
data class WalletRow(val id: String, val name: String, val fingerprint: Int, val summary: String, val usd: Double?)

/**
 * The start screen (section 2.1): the total of all wallets under a glow, with its freshness line, then one island with a row per wallet and "Pair another
 * wallet" as the one text action under them. The top bar holds Settings and the lock, and pulling down anywhere reads the balances again. [notice] names a
 * read that did not answer, and the balances kept from before stay on screen. [clock] is the time the freshness line ages by.
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
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    clock: Clock = WallClock,
    refreshByPull: Boolean = false,
    loading: Boolean = false,
) {
    val running = rememberStagger()
    Column(modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        DetailTopBar(title = "Wallets", onBack = null, onOptions = onSettings, optionsIcon = Icons.Filled.Settings, optionsLabel = "Settings")
        LumenRefreshBox(refreshByPull = refreshByPull, onRefresh = onRefresh, modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = LumioGutter, vertical = LenslySpacing.sm),
            ) {
                if (rows.isNotEmpty()) {
                    item(key = "total") { WalletsTotal(totalUsd = totalUsd, showTotal = showTotal, refreshing = refreshing, updatedAtMs = updatedAtMs, clock = clock) }
                }
                notice?.let { message ->
                    item(key = "notice") {
                        InlineBanner(message = message, tone = BannerTone.Warning, modifier = Modifier.padding(bottom = LenslySpacing.sm))
                    }
                }
                if (loading) {
                    // The balances are read on the first frame of the home; until they are, the rows keep their places.
                    item(key = "loading") { LumioIsland { LoadingRow(); IslandDivider(); LoadingRow() } }
                } else if (rows.isEmpty()) {
                    item(key = "intro") {
                        PairIntro(onScan = onPair, modifier = Modifier.fillParentMaxHeight())
                    }
                } else {
                    item(key = "wallets") {
                        LumioIsland {
                            rows.forEachIndexed { index, row ->
                                if (index > 0) IslandDivider()
                                WalletRowItem(row = row, showTotal = showTotal, running = running, index = index, onClick = { onWallet(row.id) })
                            }
                        }
                    }
                    item(key = "pair") {
                        Box(modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.xs), contentAlignment = Alignment.CenterStart) {
                            LumioTextAction(text = "Pair Another Wallet", onClick = onPair)
                        }
                    }
                }
            }
        }
    }
}

/** The hero of the list: "All wallets", their total under the glow, and the line that says how fresh it is. */
@Composable
private fun WalletsTotal(totalUsd: Double?, showTotal: Boolean, refreshing: Boolean, updatedAtMs: Long?, clock: Clock) {
    GlowBackdrop(breathing = refreshing, modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.md)) {
        Column {
            Text(
                text = "All Wallets",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TotalAmount(usd = totalUsd, shown = showTotal)
            FreshnessLine(refreshing = refreshing, updatedAtMs = updatedAtMs, clock = clock, modifier = Modifier.padding(top = LenslySpacing.xs))
        }
    }
}

/** One wallet of the list: its orb, its name and summary, and its total on the right, or nothing when it has no price. */
@Composable
private fun WalletRowItem(row: WalletRow, showTotal: Boolean, running: Boolean, index: Int, onClick: () -> Unit) {
    LumioRow(
        title = row.name,
        description = row.summary,
        leading = { WalletOrb(fingerprint = row.fingerprint) },
        trailing = if (row.usd == null) null else ({ WalletRowTotal(usd = row.usd, shown = showTotal) }),
        onClick = onClick,
        modifier = Modifier.staggeredEnter(index, running),
    )
}

/** The dollar total of a wallet row, masked while the owner hides totals. */
@Composable
private fun WalletRowTotal(usd: Double, shown: Boolean) {
    Text(
        text = Masking.amount(HomeFormat.usd(usd), hidden = !shown),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
    )
}
