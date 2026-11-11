package app.lensly.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslySpacing

/**
 * The Wallets list, reached from the See all link on Home or from the dock: every wallet as a compact card, in the same order
 * as Home, with the add tile after the last one. A long press opens the same context sheet as on Home. [wallets] is null until
 * the list has been read.
 */
@Composable
fun WalletsScreen(
    wallets: List<WalletMeta>?,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onPair: (String) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    profiles: Map<String, WalletProfile> = emptyMap(),
) {
    var sheetWallet by remember { mutableStateOf<WalletMeta?>(null) }
    LenslyScreen(
        title = "Wallets",
        modifier = modifier,
        onBack = onBack,
        scrollable = false,
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = LenslySpacing.md),
            verticalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
        ) {
            if (wallets != null) {
                itemsIndexed(wallets, key = { _, wallet -> wallet.id }) { position, wallet ->
                    WalletCard(
                        wallet = wallet,
                        profile = profiles[wallet.id] ?: WalletProfile(),
                        position = position,
                        compact = true,
                        onClick = { onOpen(wallet.id) },
                        onLongPress = { sheetWallet = wallet },
                    )
                }
                item { AddWalletTile(onClick = onAdd) }
            }
        }
    }
    sheetWallet?.let { wallet ->
        WalletContextSheet(
            wallet = wallet,
            onDismiss = { sheetWallet = null },
            onOpen = onOpen,
            onEdit = onEdit,
            onPair = onPair,
        )
    }
}
