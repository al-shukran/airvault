package app.lensly.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.app.WalletApp
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.autoAccountName
import app.lensly.app.visualIdentity
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LumioTextAction
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.profile.ProfileAvatar
import app.lensly.ui.theme.LenslySpacing

/** The note a Single Key wallet shows in place of the add action: it has no seed, so it holds no Algorand account. */
private const val SINGLE_KEY_NO_ALGORAND = "A Single Key wallet has no seed, so it cannot hold Algorand accounts."

/**
 * The Algorand App of one wallet: its Algorand accounts, one row each, with the account's Auto-Name and its full address. [addresses] holds
 * the address of each account in account order, so account N is the row at position N. The accounts are derived from the stored public keys,
 * so the seed is not read to show them.
 *
 * The overflow icon opens the App's options, the same as the Solana App's. "Add Account" is the text action under the list, and it makes one
 * account. A Single Key wallet shows a note in its place.
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
    refusal: String? = null,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberKeyHaptics()
    val listState = rememberLazyListState()
    var optionsOpen by remember { mutableStateOf(false) }
    val singleKey = wallet.kind == WalletKind.SINGLE_KEY

    AppAccountsScaffold(
        title = "Algorand Accounts",
        app = WalletApp.ALGORAND,
        walletName = wallet.name,
        count = addresses.size,
        onBack = onBack,
        onOptions = { optionsOpen = true },
        listState = listState,
        modifier = modifier,
        footer = {
            refusal?.let { InlineBanner(message = it, tone = BannerTone.Error) }
            if (singleKey) {
                InlineBanner(message = SINGLE_KEY_NO_ALGORAND, tone = BannerTone.Info)
            } else {
                LumioTextAction(text = "Add Account", onClick = onAddAccount, modifier = Modifier.tapHaptic(haptics))
            }
        },
        accounts = {
            addresses.indices.forEach { index ->
                item(key = "algorand-$index") {
                    AccountRow(
                        divider = index > 0,
                        leading = { ProfileAvatar(identity = visualIdentity(null, null, index), size = 32.dp) },
                    ) {
                        Text(
                            text = autoAccountName(wallet.autoNameBase, index),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(LenslySpacing.xxs))
                        AddressText(
                            address = addresses[index],
                            shown = addresses[index].chunked(6).joinToString(" "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            icon = false,
                        )
                    }
                }
            }
        },
    )

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
