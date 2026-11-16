package app.lumio.ui.settings

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
import app.lensly.ui.components.SettingsRow
import app.lumio.pair.PairedWallet
import app.lumio.ui.pair.PairLabels

/** The test tag of the row that pairs a wallet from Lensly. */
const val PAIR_WALLET_TAG = "pair-wallet"

/** The test tag of the confirm button of a deletion. */
const val DELETE_CONFIRM_TAG = "delete-confirm"

/** The test tag of a wallet's delete row, by the wallet's ID. */
fun deleteWalletTag(walletId: String): String = "delete-$walletId"

/**
 * The Wallets section of the settings: a row that pairs a wallet from Lensly, then each paired wallet with its fingerprint and a delete row in the
 * error colour. A deletion asks first, and it erases only Lumio's watch-only copy. The wallet in Lensly is not changed.
 */
@Composable
fun WalletsSettings(wallets: List<PairedWallet>, onPair: () -> Unit, onDelete: (PairedWallet) -> Unit) {
    var pendingDelete by remember { mutableStateOf<PairedWallet?>(null) }
    SettingsRow(
        title = "Pair from Lensly",
        description = "Add a wallet's watch-only copy. Lensly keeps the keys.",
        onClick = onPair,
        modifier = Modifier.testTag(PAIR_WALLET_TAG),
    )
    if (wallets.isEmpty()) {
        Text(
            text = "No wallets paired yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    wallets.forEach { wallet ->
        SettingsRow(
            title = wallet.name,
            description = "Fingerprint ${PairLabels.fingerprint(wallet.fingerprint)}",
        )
        SettingsRow(
            title = "Delete ${wallet.name}",
            onClick = { pendingDelete = wallet },
            destructive = true,
            modifier = Modifier.testTag(deleteWalletTag(wallet.id)),
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
