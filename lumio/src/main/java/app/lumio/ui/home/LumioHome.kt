package app.lumio.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.HomeTopBar
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.SettingsRow
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillStyle
import app.lensly.ui.wallet.WalletScaffold
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallet
import app.lumio.ui.network.NetworkSwitch
import app.lumio.ui.network.label
import app.lumio.ui.pair.PairLabels

/** The test tags of a wallet's two App rows. */
const val BITCOIN_APP_TAG = "app-bitcoin"
const val ETHEREUM_APP_TAG = "app-ethereum"

/** The test tag of the Settings row on Home. */
const val HOME_SETTINGS_TAG = "home-settings"

/**
 * Lumio's Home, which mirrors Lensly's: the top bar with the wordmark and the lock, the Mainnet and Testnet switch, then the paired wallets
 * and under each one its Apps, Bitcoin and Ethereum, as list rows. Home is the top of the app, so it has no back button. [mode] is the side of
 * the networks on show, switched with [onMode]. Each side lists only its own accounts and balances. [onBitcoin] opens a wallet's Bitcoin
 * portfolio and [onBalances] its Ethereum portfolio. [onSettings] opens Lumio's settings. [onLock] returns to the camera and locks the vault.
 * [onDelete] runs after the owner confirms a deletion. The one primary action, Pair from Lensly, is pinned under the content.
 */
@Composable
fun LumioHome(
    onLock: () -> Unit,
    onPair: () -> Unit,
    wallets: List<PairedWallet>,
    onDelete: (PairedWallet) -> Unit,
    mode: NetworkMode,
    onMode: (NetworkMode) -> Unit,
    modifier: Modifier = Modifier,
    onBitcoin: (PairedWallet) -> Unit = {},
    onBalances: (PairedWallet) -> Unit = {},
    onSettings: () -> Unit = {},
) {
    var pendingDelete by remember { mutableStateOf<PairedWallet?>(null) }
    WalletScaffold(
        modifier = modifier,
        scrollable = wallets.isNotEmpty(),
        footer = {
            WalletPill(
                text = "Pair from Lensly",
                onClick = onPair,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Primary,
            )
            Spacer(Modifier.height(LenslySpacing.xs))
        },
    ) {
        HomeTopBar(wordmark = "LUMIO", onLock = onLock)
        Spacer(Modifier.height(LenslySpacing.md))
        NetworkSwitch(mode = mode, onChange = onMode)
        if (wallets.isEmpty()) {
            Spacer(Modifier.height(LenslySpacing.md))
            EmptyState(
                icon = LenslyIcons.Wallets,
                title = "No wallets paired yet",
                body = "Pair a wallet from Lensly to see its balances and history here.",
            )
        } else {
            wallets.forEach { wallet ->
                PairedWalletSection(
                    wallet = wallet,
                    mode = mode,
                    onBitcoin = { onBitcoin(wallet) },
                    onBalances = { onBalances(wallet) },
                    onDelete = { pendingDelete = wallet },
                )
            }
        }
        SettingsRow(
            title = "Settings",
            description = "Bitcoin servers, Ethereum chains and data providers",
            onClick = onSettings,
            modifier = Modifier.testTag(HOME_SETTINGS_TAG),
        )
    }
    pendingDelete?.let { wallet ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${wallet.name}?") },
            text = { Text("Lumio erases this wallet's watch-only copy. The wallet in Lensly is not changed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(wallet)
                    },
                    modifier = Modifier.testTag(DELETE_CONFIRM_TAG),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

/**
 * A paired wallet and its Apps, as Lensly lists them. Bitcoin is listed when the wallet holds an account on the chosen side, and Ethereum
 * when it holds an account key, since every EVM network is in both sides. A wallet with neither on this side says so. Deleting is the last
 * row, in the error colour.
 */
@Composable
private fun PairedWalletSection(
    wallet: PairedWallet,
    mode: NetworkMode,
    onBitcoin: () -> Unit,
    onBalances: () -> Unit,
    onDelete: () -> Unit,
) {
    val bitcoinAccounts = wallet.bitcoin.count { mode.holds(it.network) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(LenslySpacing.lg))
        Text(wallet.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "Fingerprint ${PairLabels.fingerprint(wallet.fingerprint)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (bitcoinAccounts == 0 && wallet.evm == null) {
            Spacer(Modifier.height(LenslySpacing.xs))
            Text(
                "No accounts on ${mode.label()} in this wallet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (bitcoinAccounts > 0) {
        SettingsRow(
            title = "Bitcoin",
            description = "${accountsText(bitcoinAccounts)} on ${mode.label()}",
            onClick = onBitcoin,
            modifier = Modifier.testTag(BITCOIN_APP_TAG),
        )
    }
    if (wallet.evm != null) {
        SettingsRow(
            title = "Ethereum",
            description = "Balances on ${mode.label().lowercase()} networks",
            onClick = onBalances,
            modifier = Modifier.testTag(ETHEREUM_APP_TAG),
        )
    }
    SettingsRow(title = "Delete", onClick = onDelete, destructive = true)
}

private fun accountsText(count: Int): String = if (count == 1) "1 account" else "$count accounts"

/** The test tag of the confirm button of a deletion. */
const val DELETE_CONFIRM_TAG = "delete-confirm"
