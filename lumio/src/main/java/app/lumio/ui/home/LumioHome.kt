package app.lumio.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallet
import app.lumio.ui.network.NetworkSwitch
import app.lumio.ui.network.label
import app.lumio.ui.pair.PairLabels

/** The test tags of a wallet's two App rows. */
const val BITCOIN_APP_TAG = "app-bitcoin"
const val ETHEREUM_APP_TAG = "app-ethereum"

/**
 * Lumio's Home, which mirrors Lensly's: the paired wallets, and under each one its Apps, Bitcoin and Ethereum. [mode] is the side of the
 * networks on show, switched at the top with [onMode]. Each side lists only its own accounts and balances. [onBitcoin] opens a wallet's
 * Bitcoin portfolio and [onBalances] its Ethereum portfolio. [onSettings] opens Lumio's settings. Home is the root screen, so it has no
 * back arrow: the system back leaves it. [onDelete] runs after the owner confirms a deletion.
 */
@Composable
fun LumioHome(
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
    LenslyScreen(
        title = "Lumio",
        modifier = modifier,
        scrollable = wallets.isNotEmpty(),
        bottomAction = { PillButton(text = "Pair from Lensly", onClick = onPair) },
    ) {
        NetworkSwitch(mode = mode, onChange = onMode)
        Spacer(Modifier.height(LenslySpacing.md))
        if (wallets.isEmpty()) {
            EmptyState(
                icon = LenslyIcons.Wallets,
                title = "No wallets paired yet",
                body = "Pair a wallet from Lensly to see its balances and history here.",
            )
        } else {
            wallets.forEach { wallet ->
                PairedWalletCard(
                    wallet = wallet,
                    mode = mode,
                    onBitcoin = { onBitcoin(wallet) },
                    onBalances = { onBalances(wallet) },
                    onDelete = { pendingDelete = wallet },
                )
                Spacer(Modifier.height(LenslySpacing.sm))
            }
        }
        PillButton(text = "Settings", onClick = onSettings)
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
 * when it holds an account key, since every EVM network is in both sides. A wallet with neither on this side says so.
 */
@Composable
private fun PairedWalletCard(
    wallet: PairedWallet,
    mode: NetworkMode,
    onBitcoin: () -> Unit,
    onBalances: () -> Unit,
    onDelete: () -> Unit,
) {
    val bitcoinAccounts = wallet.bitcoin.count { mode.holds(it.network) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(LenslyRadius.md))
            .padding(LenslySpacing.md),
    ) {
        Text(wallet.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "Fingerprint ${PairLabels.fingerprint(wallet.fingerprint)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        if (bitcoinAccounts == 0 && wallet.evm == null) {
            Text(
                "No accounts on ${mode.label()} in this wallet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(LenslySpacing.sm))
        }
        if (bitcoinAccounts > 0) {
            AppRow(
                title = "Bitcoin",
                caption = "${accountsText(bitcoinAccounts)} on ${mode.label()}",
                tag = BITCOIN_APP_TAG,
                onClick = onBitcoin,
            )
            Spacer(Modifier.height(LenslySpacing.xs))
        }
        if (wallet.evm != null) {
            AppRow(
                title = "Ethereum",
                caption = "Balances on ${mode.label().lowercase()} networks",
                tag = ETHEREUM_APP_TAG,
                onClick = onBalances,
            )
            Spacer(Modifier.height(LenslySpacing.sm))
        }
        PillButton(text = "Delete", onClick = onDelete)
    }
}

/** One App of a wallet: its name, and what it shows on the chosen side. A tap opens that App's portfolio. */
@Composable
private fun AppRow(title: String, caption: String, tag: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(LenslyRadius.md))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(LenslySpacing.md),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun accountsText(count: Int): String = if (count == 1) "1 account" else "$count accounts"

/** The test tag of the confirm button of a deletion. */
const val DELETE_CONFIRM_TAG = "delete-confirm"
