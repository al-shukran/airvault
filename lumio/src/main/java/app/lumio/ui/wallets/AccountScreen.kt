package app.lumio.ui.wallets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lensly.core.registry.Amounts
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslySpacing
import app.lumio.chains.ChainHolding
import app.lumio.home.Masking
import app.lumio.tokens.TokenHolding
import app.lumio.transactions.TxFeedItem
import app.lumio.ui.transactions.TxCard
import app.lumio.wallets.AccountChain
import app.lumio.wallets.WalletAccount
import java.math.BigInteger
import java.time.ZoneId

/**
 * One account: its chain and network, its full address, its balance, and the two text actions, Send and Receive. An Ethereum account also lists its
 * ERC-20 balances, and every account lists its newest [activity], each row opening its transaction.
 */
@Composable
fun AccountScreen(
    walletName: String,
    account: WalletAccount,
    showBalance: Boolean,
    tokens: List<TokenHolding> = emptyList(),
    chainHoldings: List<ChainHolding> = emptyList(),
    activity: List<TxFeedItem> = emptyList(),
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onOpenTransaction: (TxFeedItem) -> Unit = {},
    refreshing: Boolean = false,
    notice: String? = null,
    onRefresh: (() -> Unit)? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    LenslyScreen(title = account.chain.label, modifier = modifier, onBack = onBack) {
        Text(
            text = walletName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        Field(label = "Network", value = account.networkName)
        account.script?.let { Field(label = "Script", value = it) }
        Text(
            text = "Address",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AddressText(address = account.address, icon = false)
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = "Balance",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BalanceValue(account, shown = showBalance, large = true)
        Spacer(Modifier.height(LenslySpacing.lg))
        TextButton(onClick = onSend) { Text("Send") }
        TextButton(onClick = onReceive) { Text("Receive") }
        if (refreshing) {
            Text(
                text = "Reading the network",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        notice?.let {
            Spacer(Modifier.height(LenslySpacing.sm))
            InlineBanner(message = it, tone = BannerTone.Warning)
        }
        onRefresh?.let { refresh -> TextButton(onClick = refresh, enabled = !refreshing) { Text("Refresh") } }
        if (account.chain == AccountChain.ETHEREUM || chainHoldings.isNotEmpty()) {
            Spacer(Modifier.height(LenslySpacing.lg))
            Text(
                text = "Tokens",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val held = tokens.filter { it.amount.signum() > 0 }
            if (held.isEmpty() && chainHoldings.isEmpty()) {
                Text(
                    text = "No token balances yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            held.forEach { holding -> HeldTokenLine(holding = holding, shown = showBalance) }
            chainHoldings.forEach { holding ->
                TokenLine(
                    symbol = holding.symbol,
                    network = holding.network,
                    amount = holding.amount,
                    decimals = holding.decimals,
                    shown = showBalance,
                )
            }
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        Text(
            text = "Activity",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            account.chain == AccountChain.SOLANA || account.chain == AccountChain.ALGORAND -> Text(
                text = "Activity is not read for ${account.chain.label} yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            activity.isEmpty() -> Text(
                text = "No transactions yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> activity.forEach { item ->
                TxCard(item = item, zone = zone, onClick = { onOpenTransaction(item) })
            }
        }
    }
}

/** One ERC-20 token the address holds on a network, with the balance, hidden when the owner hides balances. */
@Composable
private fun HeldTokenLine(holding: TokenHolding, shown: Boolean) =
    TokenLine(holding.token.symbol, holding.chain.name, holding.amount, holding.token.decimals, shown)

/** One token line: its symbol and its network, with the amount in whole units, hidden when the owner hides balances. */
@Composable
private fun TokenLine(symbol: String, network: String, amount: BigInteger, decimals: Int, shown: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LenslySpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = symbol,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = network,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = Masking.amount(Amounts.format(amount, decimals), hidden = !shown),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A label above a value on the account page. */
@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(LenslySpacing.sm))
    }
}
