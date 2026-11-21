package app.lumio.ui.wallets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.core.registry.NetworkEntry
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.IslandDivider
import app.lensly.ui.components.LumioIsland
import app.lensly.ui.theme.LenslySpacing
import app.lumio.data.Clock
import app.lumio.data.WallClock
import app.lumio.network.NetworkMode
import app.lumio.ui.kit.GlowBackdrop
import app.lumio.ui.kit.WalletOrb
import app.lumio.ui.network.label
import app.lumio.ui.theme.LumioGutter
import app.lumio.wallets.WalletAccount
import app.lumio.wallets.WalletAccounts

/**
 * One paired wallet (section 2.1, Wallet), as the body of its page: the orb and total under a glow, the side and the summary of its accounts, and the
 * freshness line. Each chain of the wallet is an island of its accounts, with the address and the token count of each. An account opens its own page.
 * The page's top bar is the shell's, so this has none, and the page does not pull to refresh. [tokenCounts] holds the tokens each Ethereum account
 * holds, and [network] looks up the logo of its network. [notice] names a read that did not answer.
 */
@Composable
fun WalletScreen(
    fingerprint: Int,
    mode: NetworkMode,
    accounts: List<WalletAccount>,
    tokenCounts: Map<WalletAccount, Int>,
    network: (Long) -> NetworkEntry?,
    totalUsd: Double?,
    showTotal: Boolean,
    showBalances: Boolean,
    refreshing: Boolean,
    updatedAtMs: Long?,
    notice: String?,
    onAccount: (WalletAccount) -> Unit,
    modifier: Modifier = Modifier,
    clock: Clock = WallClock,
) {
    // A pushed wallet is populated on its first frame, so its rows do not stagger in. The Wallets home alone staggers its list.
    val running = false
    val evmChainId = WalletAccounts.primaryEvmChainId(mode)
    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = LumioGutter, vertical = LenslySpacing.sm),
        ) {
            item(key = "hero") {
                WalletHero(
                    fingerprint = fingerprint,
                    mode = mode,
                    accounts = accounts,
                    totalUsd = totalUsd,
                    showTotal = showTotal,
                    refreshing = refreshing,
                    updatedAtMs = updatedAtMs,
                    clock = clock,
                )
            }
            notice?.let { message ->
                item(key = "notice") {
                    InlineBanner(message = message, tone = BannerTone.Warning, modifier = Modifier.padding(bottom = LenslySpacing.sm))
                }
            }
            if (accounts.isEmpty()) {
                item(key = "none") {
                    Text(
                        text = "This wallet has no accounts on ${mode.label().lowercase()} yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = LenslySpacing.md),
                    )
                }
            }
            accountsByChain(accounts).forEach { (family, group) ->
                item(key = "chain-$family") {
                    ChainIsland(
                        family = family,
                        group = group,
                        accounts = accounts,
                        mode = mode,
                        evmChainId = evmChainId,
                        network = network,
                        tokenCounts = tokenCounts,
                        showBalances = showBalances,
                        running = running,
                        onAccount = onAccount,
                    )
                }
            }
        }
    }
}

/** The hero of a wallet: its orb at 72 dp and its total under a glow, then its side and summary, and the freshness line. */
@Composable
private fun WalletHero(
    fingerprint: Int,
    mode: NetworkMode,
    accounts: List<WalletAccount>,
    totalUsd: Double?,
    showTotal: Boolean,
    refreshing: Boolean,
    updatedAtMs: Long?,
    clock: Clock,
) {
    GlowBackdrop(breathing = refreshing, modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.md)) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            WalletOrb(fingerprint = fingerprint, diameter = 72.dp)
            Spacer(Modifier.height(LenslySpacing.sm))
            TotalAmount(usd = totalUsd, shown = showTotal)
            Text(
                text = "${mode.label()} · ${summaryLine(accounts)}",
                modifier = Modifier.padding(top = LenslySpacing.xs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FreshnessLine(refreshing = refreshing, updatedAtMs = updatedAtMs, clock = clock, modifier = Modifier.padding(top = LenslySpacing.xs))
        }
    }
}

/** The accounts of one chain [family] as an island under the family's name. A row's place in [accounts] sets its delay in the enter stagger. */
@Composable
private fun ChainIsland(
    family: String,
    group: List<WalletAccount>,
    accounts: List<WalletAccount>,
    mode: NetworkMode,
    evmChainId: Long,
    network: (Long) -> NetworkEntry?,
    tokenCounts: Map<WalletAccount, Int>,
    showBalances: Boolean,
    running: Boolean,
    onAccount: (WalletAccount) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = LenslySpacing.md)) {
        Text(
            text = WalletAccounts.familyLabel(family),
            modifier = Modifier.padding(bottom = LenslySpacing.xs),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        LumioIsland {
            group.forEachIndexed { position, account ->
                if (position > 0) IslandDivider()
                AccountRowItem(
                    account = account,
                    icon = accountIconSpec(account, evmChainId, network),
                    tokens = tokenCounts[account] ?: 0,
                    mode = mode,
                    showBalance = showBalances,
                    running = running,
                    index = accounts.indexOf(account),
                    onClick = { onAccount(account) },
                )
            }
        }
    }
}
