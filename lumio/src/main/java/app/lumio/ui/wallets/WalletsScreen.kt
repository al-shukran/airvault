package app.lumio.ui.wallets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.HomeTopBar
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.SettingsRow
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WALLET_GUTTER
import app.lumio.ui.home.LumioMark
import app.lensly.ui.theme.LocalLumenPalette

/** One row of the Wallets list: the wallet's [id], its [name], and the [detail] under it, a total in dollars or the count of its accounts per chain. */
data class WalletRow(val id: String, val name: String, val detail: String)

/**
 * The start screen: one row per paired wallet, each opening the wallet. Under the rows, "Pair a Wallet" is the one text action. [notice] names a read
 * that did not answer, and pull to refresh reads the balances again. The top bar holds the lock, as on every Lumio screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletsScreen(
    rows: List<WalletRow>,
    refreshing: Boolean,
    notice: String?,
    onWallet: (String) -> Unit,
    onPair: () -> Unit,
    onRefresh: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        HomeTopBar(wordmark = "LUMIO", onLock = onLock, mark = { LumioMark() })
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = WALLET_GUTTER, vertical = LenslySpacing.sm),
            ) {
                notice?.let { message ->
                    item(key = "notice") {
                        InlineBanner(message = message, tone = BannerTone.Warning, modifier = Modifier.padding(bottom = LenslySpacing.sm))
                    }
                }
                if (rows.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = "No wallets paired yet. Pair a wallet from Lensly to see its accounts here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = LenslySpacing.md),
                        )
                    }
                }
                items(rows, key = { it.id }) { row ->
                    SettingsRow(title = row.name, description = row.detail, onClick = { onWallet(row.id) })
                }
                item(key = "pair") {
                    TextButton(onClick = onPair, colors = ButtonDefaults.textButtonColors(contentColor = LocalLumenPalette.current.accentInk)) { Text("Pair a Wallet") }
                }
            }
        }
    }
}
