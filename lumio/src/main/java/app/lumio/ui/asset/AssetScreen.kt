package app.lumio.ui.asset

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.lensly.core.registry.NetworkEntry
import app.lensly.ui.components.AppTitle
import app.lensly.ui.components.BitcoinIcon
import app.lensly.ui.components.ChainIcon
import app.lensly.ui.components.DetailTopBar
import app.lensly.ui.components.SettingsRow
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletScaffold
import app.lumio.data.net.Family
import app.lumio.home.AccountOption
import app.lumio.home.HomeAsset
import app.lumio.home.HomeFormat
import app.lumio.home.Masking

/** The test tag of the note that the chart and the transactions are coming. */
const val ASSET_SOON_TAG = "asset-soon"

/**
 * The page of one coin: its network, its balance and its value, then the accounts that hold it. Each account opens that wallet's own page, where its
 * addresses and sends are. [hidden] masks the balance and the value, as the settings ask. The chart and the transactions are coming, and this page
 * says so until they are built.
 */
@Composable
fun AssetScreen(
    asset: HomeAsset,
    entry: NetworkEntry?,
    accounts: List<AccountOption>,
    hidden: Boolean,
    onAccount: (AccountOption) -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WalletScaffold(modifier = modifier) {
        DetailTopBar(onBack = onBack, onLock = onLock)
        AppTitle(
            logo = { size ->
                if (asset.network.family == Family.BTC) {
                    BitcoinIcon(size = size)
                } else {
                    ChainIcon(network = entry, size = size)
                }
            },
            name = asset.symbol,
            walletName = if (asset.testnet) "${asset.name} · Testnet" else asset.name,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = Masking.amount("${HomeFormat.coins(asset.balance)} ${asset.symbol}", hidden),
            style = MaterialTheme.typography.headlineMedium,
            color = LenslyOnSurface,
        )
        Text(
            text = Masking.amount(asset.usd?.let { HomeFormat.usd(it) } ?: "—", hidden),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            text = "Chart and transactions coming soon",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ASSET_SOON_TAG),
        )
        if (accounts.isNotEmpty()) {
            Spacer(Modifier.height(LenslySpacing.md))
            WalletEyebrow("Accounts")
            Spacer(Modifier.height(LenslySpacing.xs))
            accounts.forEach { option ->
                SettingsRow(title = option.title, description = option.detail, onClick = { onAccount(option) })
            }
        }
    }
}
