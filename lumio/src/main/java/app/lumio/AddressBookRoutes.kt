package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.ui.settings.AddressBookScreen

/** The Address Book of the paired wallet [walletId]. The wallet's book is replaced by its next pairing from Lensly. */
@Composable
internal fun AddressBookRoute(navController: NavHostController, holder: VaultHolder, walletId: String, onLock: () -> Unit) {
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    val wallet by produceState<PairedWallet?>(initialValue = null, session, walletId) {
        value = if (session == null) null else onVault(null) { PairedWallets.list(session).firstOrNull { it.id == walletId } }
    }
    wallet?.let { paired ->
        AddressBookScreen(
            walletName = paired.name,
            entries = paired.addressBook,
            offline = rememberSettingsOffline(),
            onBack = { navController.popBackStack() },
            onLock = onLock,
        )
    }
}
