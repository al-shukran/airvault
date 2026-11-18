package app.lensly.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.lensly.app.WalletApp
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.autoAccountName
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.DetailTopBar
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletScaffold

/** The note a Single Key wallet shows in place of the add row: it has no seed, so it holds no Algorand account. */
private const val SINGLE_KEY_NO_ALGORAND = "A Single Key wallet has no seed, so it cannot hold Algorand accounts."

/**
 * The Algorand App of one wallet: its Algorand accounts, one row each, with the account's Auto-Name and its full address. [addresses] holds
 * the address of each account in account order, so account N is the row at position N. The accounts are derived from the stored public keys,
 * so the seed is not read to show them.
 *
 * The overflow icon opens the App's options, the same as the Solana App's. "Add Account" is the last row, and it makes one account. A Single
 * Key wallet shows a note in its place.
 */
@Composable
fun AlgorandAccountsScreen(
    wallet: WalletMeta,
    addresses: List<String>,
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    onBulkGenerate: () -> Unit,
    onPair: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var optionsOpen by remember { mutableStateOf(false) }
    val singleKey = wallet.kind == WalletKind.SINGLE_KEY

    WalletScaffold(
        modifier = modifier,
        scrollable = false,
        topBar = {
            DetailTopBar(
                title = "Algorand Accounts",
                onBack = onBack,
                onOptions = { optionsOpen = true },
                optionsLabel = "App options",
            )
        },
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            item(key = "app") { AppHeader(app = WalletApp.ALGORAND, walletName = wallet.name) }
            item(key = "accounts-header") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(LenslySpacing.lg))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        WalletEyebrow("Accounts")
                        Spacer(Modifier.width(LenslySpacing.xs))
                        WalletEyebrow("${addresses.size}")
                    }
                    Spacer(Modifier.height(LenslySpacing.xxs))
                }
            }
            items(addresses.indices.toList(), key = { index -> "algorand-$index" }) { index ->
                AlgorandAccountRow(name = autoAccountName(wallet.autoNameBase, index), address = addresses[index])
            }
            item(key = "add") {
                Spacer(Modifier.height(LenslySpacing.md))
                if (singleKey) {
                    InlineBanner(message = SINGLE_KEY_NO_ALGORAND, tone = BannerTone.Info)
                } else {
                    AddAccountRow(label = "Add Account", onClick = onAddAccount)
                }
            }
        }
    }

    if (optionsOpen) {
        AppOptionsSheet(
            app = WalletApp.ALGORAND,
            onDismiss = { optionsOpen = false },
            onGenerate = if (singleKey) null else ({
                optionsOpen = false
                onBulkGenerate()
            }),
            onSelect = null,
            onPair = {
                optionsOpen = false
                onPair()
            },
            onScan = {
                optionsOpen = false
                onScan()
            },
        )
    }
}

/** One Algorand account: its Auto-Name, then its full address, which wraps at the spaces between its groups of six. */
@Composable
private fun AlgorandAccountRow(name: String, address: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.sm)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(LenslySpacing.xxs))
            AddressText(
                address = address,
                shown = address.chunked(6).joinToString(" "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                icon = false,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
