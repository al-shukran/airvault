package app.lensly.ui.navigation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.app.WalletProfile
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.home.WalletCard
import app.lensly.ui.theme.LenslySpacing

// TEMP until global scan lands. The dock's Scan opens this route. With one wallet it goes straight to that wallet's SIGN flow,
// and with several it asks which wallet to sign with first. Delete this file, and its registration in walletRoutes, once the
// global scan route exists on master.

/** Picks the wallet a scan signs with, then replaces this route with that wallet's SIGN flow. */
@Composable
internal fun ScanAnyRoute(navController: NavHostController, holder: VaultHolder) {
    val session = rememberOpenSession(navController, holder) ?: return
    val listing = rememberWalletListing(navController, holder, session) ?: return
    val wallets = listing.wallets
    val sign: (String) -> Unit = { walletId ->
        navController.navigate(HiddenRoute.scan(walletId)) {
            popUpTo(HiddenRoute.SCAN_ANY) { inclusive = true }
        }
    }
    LaunchedEffect(wallets) {
        when (wallets.size) {
            0 -> navController.popBackStack()
            1 -> sign(wallets.single().id)
        }
    }
    if (wallets.size < 2) return
    LenslyScreen(title = "Scan", onBack = { navController.popBackStack() }) {
        Text(
            text = "Choose the wallet to sign with.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.lg))
        wallets.forEachIndexed { position, wallet ->
            WalletCard(
                wallet = wallet,
                profile = listing.profiles[wallet.id] ?: WalletProfile(),
                position = position,
                compact = true,
                onClick = { sign(wallet.id) },
            )
            Spacer(Modifier.height(LenslySpacing.sm))
        }
    }
}
