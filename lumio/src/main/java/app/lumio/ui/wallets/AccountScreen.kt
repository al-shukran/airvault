package app.lumio.ui.wallets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LumenMono
import app.lumio.home.HomeFormat
import app.lumio.home.Masking
import app.lumio.transactions.TxFeedItem
import app.lumio.ui.home.AssetIcon
import app.lumio.ui.kit.AnimatedAmount
import app.lumio.ui.kit.LumioAssetIcon
import app.lumio.ui.kit.LumioIsland
import app.lumio.ui.kit.LumioPrimaryButton
import app.lumio.ui.kit.LumioRow
import app.lumio.ui.kit.LumioTextAction
import app.lumio.ui.kit.SharedKeys
import app.lumio.ui.kit.lumioSharedBounds
import app.lumio.ui.kit.lumioSharedElement
import app.lumio.ui.kit.rememberCopyAction
import app.lumio.ui.transactions.ActivityRow
import app.lumio.wallets.AccountChain
import app.lumio.wallets.WalletAccount
import java.time.ZoneId

/** The hero's text while no read of the account's balance is kept. */
private const val NOT_READ = "Not read yet"

/**
 * One account (section 2.1, Account): its balance as the hero with its value in dollars, then its network and address, which copy when tapped. Its
 * assets and its newest activity follow, each in an island. Send is the primary action, with Receive as the text action above it. An account that
 * holds nothing makes Receive the primary, and Send drops out.
 */
@Composable
internal fun AccountScreen(
    walletName: String,
    account: WalletAccount,
    showBalance: Boolean,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    assets: List<AssetRowModel> = emptyList(),
    activity: List<TxFeedItem> = emptyList(),
    onAsset: (AssetRowModel) -> Unit = {},
    onAddAsset: (() -> Unit)? = null,
    onOpenTransaction: (TxFeedItem) -> Unit = {},
    refreshing: Boolean = false,
    notice: String? = null,
    onRefresh: (() -> Unit)? = null,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val balance = account.balance
    val empty = assets.isNotEmpty() && assets.all { it.zero }
    val heroText = balance?.let { "${HomeFormat.coins(it.balance)} ${it.symbol}" }
    val native = assets.firstOrNull { it.isNative }
    val heroFiat = native?.fiatText
    LenslyScreen(
        title = account.chain.label,
        modifier = modifier,
        onBack = onBack,
        bottomAction = {
            if (empty) {
                LumioPrimaryButton(text = "Receive", onClick = onReceive)
            } else {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LumioTextAction(text = "Receive", onClick = onReceive)
                }
                Spacer(Modifier.height(LenslySpacing.xs))
                LumioPrimaryButton(text = "Send", onClick = onSend)
            }
        },
    ) {
        Text(text = walletName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(LenslySpacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            native?.let { coin ->
                LumioAssetIcon(
                    testnet = coin.testnet,
                    modifier = Modifier.lumioSharedElement(SharedKeys.accountIcon(account.walletId, account.chain.name, account.index)),
                ) {
                    AssetIcon(spec = coin.icon, size = 24.dp)
                }
                Spacer(Modifier.width(LenslySpacing.sm))
            }
            if (heroText == null) {
                Text(text = NOT_READ, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                AnimatedAmount(
                    text = heroText,
                    hidden = !showBalance,
                    modifier = Modifier.lumioSharedBounds(SharedKeys.accountBalance(account.walletId, account.chain.name, account.index)),
                )
            }
        }
        heroFiat?.let { fiat ->
            Text(
                text = Masking.amount(fiat, hidden = !showBalance),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(LenslySpacing.md))
        AddressLine(network = account.networkName, address = account.address)
        notice?.let {
            Spacer(Modifier.height(LenslySpacing.sm))
            InlineBanner(message = it, tone = BannerTone.Warning)
        }
        if (refreshing) {
            Text(
                text = "Reading the network",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        onRefresh?.let { refresh ->
            LumioTextAction(text = "Refresh", onClick = refresh, enabled = !refreshing)
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        SectionTitle("Assets")
        LumioIsland {
            if (assets.isEmpty()) {
                EmptyLine(if (balance == null) NOT_READ else "No assets yet.")
            } else {
                assets.forEachIndexed { index, asset ->
                    if (index > 0) Divider()
                    AssetRow(asset = asset, shown = showBalance, onClick = { onAsset(asset) })
                }
            }
            onAddAsset?.let { addAsset ->
                if (assets.isNotEmpty()) Divider()
                LumioRow(title = "Add Asset", onClick = addAsset)
            }
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        SectionTitle("Activity")
        LumioIsland {
            when {
                account.chain == AccountChain.SOLANA || account.chain == AccountChain.ALGORAND ->
                    EmptyLine("Activity is not read for ${account.chain.label} yet.")
                activity.isEmpty() -> EmptyLine("No transactions yet.")
                else -> activity.forEachIndexed { index, item ->
                    if (index > 0) Divider()
                    ActivityRow(item = item, zone = zone, shown = showBalance, onClick = { onOpenTransaction(item) })
                }
            }
        }
    }
}

/**
 * The network and the address of the account, the address in mono. A tap copies the address, and the address reads "Copied" for 1.5 s.
 */
@Composable
private fun AddressLine(network: String, address: String) {
    val copy = rememberCopyAction(value = address, label = "Address")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = copy.copy),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$network · ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (copy.copied) "Copied" else shortAddress(address),
            style = LumenMono,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** One asset row of the Assets island: its logo, its symbol and network, and its amount with its value in dollars. A tap opens the asset. */
@Composable
private fun AssetRow(asset: AssetRowModel, shown: Boolean, onClick: () -> Unit) {
    LumioRow(
        title = asset.symbol,
        description = asset.network,
        leading = { LumioAssetIcon(testnet = asset.testnet) { AssetIcon(spec = asset.icon, size = 24.dp) } },
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Masking.amount(asset.amountText, hidden = !shown),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                )
                asset.fiatText?.let { fiat ->
                    Text(
                        text = Masking.amount(fiat, hidden = !shown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
        },
        onClick = onClick,
    )
}

/** The label of an island, in the label face and sentence case. */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = LenslySpacing.xs),
    )
}

/** A line of an island that says there is nothing to list. */
@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.sm),
    )
}

/** The hairline between two rows of an island. */
@Composable
private fun Divider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = LenslySpacing.md),
    )
}
