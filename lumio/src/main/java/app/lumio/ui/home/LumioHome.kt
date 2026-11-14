package app.lumio.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.PillChip
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lumio.pair.PairedWallet
import app.lumio.ui.pair.PairLabels
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Lumio's Home: the paired wallets, each with its name, its family and its networks, and the action that pairs a wallet from Lensly.
 * [onLock] returns to the camera and locks the vault. [onDelete] runs after the owner confirms a deletion.
 */
@Composable
fun LumioHome(
    onLock: () -> Unit,
    onPair: () -> Unit,
    wallets: List<PairedWallet>,
    onDelete: (PairedWallet) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<PairedWallet?>(null) }
    LenslyScreen(
        title = "Lumio",
        modifier = modifier,
        onBack = onLock,
        scrollable = wallets.isNotEmpty(),
        bottomAction = { PillButton(text = "Pair from Lensly", onClick = onPair) },
    ) {
        if (wallets.isEmpty()) {
            EmptyState(
                icon = LenslyIcons.Wallets,
                title = "No wallets paired yet",
                body = "Pair a wallet from Lensly to see its balances and history here.",
            )
        } else {
            wallets.forEach { wallet ->
                PairedWalletRow(wallet = wallet, onDelete = { pendingDelete = wallet })
                Spacer(Modifier.height(LenslySpacing.sm))
            }
        }
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

@Composable
private fun PairedWalletRow(wallet: PairedWallet, onDelete: () -> Unit) {
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
        Spacer(Modifier.height(LenslySpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
            wallet.families.forEach { family -> PillChip(text = PairLabels.family(family)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
            networkLabels(wallet).forEach { label -> PillChip(text = label) }
        }
        Spacer(Modifier.height(LenslySpacing.sm))
        PillButton(text = "Delete", onClick = onDelete)
    }
}

/** The test tag of the confirm button of a deletion. */
const val DELETE_CONFIRM_TAG = "delete-confirm"

/** The network badges of a wallet: every EVM network for an account key, and each Bitcoin network its Accounts are on. */
private fun networkLabels(wallet: PairedWallet): List<String> = buildList {
    if (wallet.evm != null) add("All EVM networks")
    wallet.bitcoin.map { it.network }.distinct().forEach { add(PairLabels.network(it)) }
}
