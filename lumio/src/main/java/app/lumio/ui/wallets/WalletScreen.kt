package app.lumio.ui.wallets

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.SettingsRow
import app.lensly.ui.theme.LenslySpacing
import app.lumio.home.HomeFormat
import app.lumio.network.NetworkMode
import app.lumio.ui.network.label
import app.lumio.wallets.WalletAccount

/**
 * One paired wallet: its name, then each of its accounts on the side [mode] chooses, with the chain, the short address and the balance. Tapping an
 * account opens it. [refreshing] shows a read in progress, and "Refresh" reads the balances again. [notice] names a read that did not answer.
 */
@Composable
fun WalletScreen(
    walletName: String,
    mode: NetworkMode,
    accounts: List<WalletAccount>,
    totalUsd: Double?,
    showTotal: Boolean,
    showBalances: Boolean,
    refreshing: Boolean,
    notice: String?,
    onAccount: (WalletAccount) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(title = walletName, modifier = modifier, onBack = onBack) {
        WalletTotal(totalUsd = totalUsd, showTotal = showTotal)
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = "${mode.label()} accounts",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        if (refreshing) {
            Text(
                text = "Reading the network",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        notice?.let {
            InlineBanner(message = it, tone = BannerTone.Warning)
            Spacer(Modifier.height(LenslySpacing.sm))
        }
        if (accounts.isEmpty()) {
            Text(
                text = "This wallet has no accounts on ${mode.label().lowercase()} yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        accounts.forEach { account ->
            SettingsRow(
                title = account.chain.label,
                description = listOfNotNull(account.script, shortAddress(account.address)).joinToString(" · "),
                onClick = { onAccount(account) },
                preview = { BalanceValue(account, shown = showBalances) },
            )
        }
        TextButton(onClick = onRefresh, enabled = !refreshing) { Text("Refresh") }
    }
}

/**
 * The total of the wallet: "Total Hidden" while the owner keeps totals off, its value in dollars when a balance has a price, else "No price yet". The
 * testnet coins have no price, so a testnet wallet reads "No price yet".
 */
@Composable
private fun WalletTotal(totalUsd: Double?, showTotal: Boolean) {
    Text(
        text = "Total",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = when {
            !showTotal -> "Total Hidden"
            totalUsd != null -> HomeFormat.usd(totalUsd)
            else -> "No price yet"
        },
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
