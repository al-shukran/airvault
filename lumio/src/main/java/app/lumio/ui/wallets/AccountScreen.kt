package app.lumio.ui.wallets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslySpacing
import app.lumio.wallets.AccountChain
import app.lumio.wallets.WalletAccount

/**
 * One account: its chain and network, its full address, its balance, and the two text actions, Send and Receive. Send is not offered on Solana or
 * Algorand yet, so it reads "Coming soon" there.
 */
@Composable
fun AccountScreen(
    walletName: String,
    account: WalletAccount,
    showBalance: Boolean,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sendable = account.chain == AccountChain.BITCOIN || account.chain == AccountChain.ETHEREUM
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
        TextButton(onClick = onSend, enabled = sendable) { Text("Send") }
        if (!sendable) {
            Text(
                text = "Coming soon",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onReceive) { Text("Receive") }
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
