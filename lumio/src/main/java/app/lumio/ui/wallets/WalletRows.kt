package app.lumio.ui.wallets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.LenslySpacing
import app.lumio.home.HomeFormat
import app.lumio.home.Masking
import app.lumio.wallets.AccountChain
import app.lumio.wallets.WalletAccount

/** The balance that an account shows when no read of it is kept. */
private const val NOT_READ = "Not read yet"

/**
 * The count of accounts on each chain, such as "2 Ethereum, 1 Bitcoin", in chain order. A wallet with no account on the side reads "No accounts".
 */
internal fun chainCounts(accounts: List<WalletAccount>): String {
    val counts = accounts.groupingBy { it.chain }.eachCount()
    if (counts.isEmpty()) return "No accounts"
    return AccountChain.entries.mapNotNull { chain -> counts[chain]?.let { "$it ${chain.label}" } }.joinToString(", ")
}

/** An address as a list shows it: its first eight and last six characters. */
internal fun shortAddress(address: String): String = "${address.take(8)}…${address.takeLast(6)}"

/**
 * The balance of [account] in its coin, such as "1.5 MON", over the network it is read on. It reads "Not read yet" while no read is kept, and the mask
 * replaces the amount while the owner hides balances.
 */
@Composable
internal fun BalanceValue(account: WalletAccount, shown: Boolean) {
    val amount = account.balance?.let { "${HomeFormat.coins(it.balance)} ${it.symbol}" } ?: NOT_READ
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = Masking.amount(amount, hidden = !shown),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(LenslySpacing.xxs))
        Text(
            text = account.networkName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
