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
            entries = paired.addressBook,
            offline = rememberSettingsOffline(),
            onEdit = { address -> navController.navigate(LumioRoute.addressHandoff(address, edit = true)) },
            onBack = { navController.popBackStack() },
            onLock = onLock,
        )
    }
}

/** The Address Book page of Settings: the books of every paired wallet as one, read again when [listVersion] changes. An entry two wallets share shows once. */
@Composable
internal fun AddressBookSettingsRoute(navController: NavHostController, holder: VaultHolder, listVersion: Int, onLock: () -> Unit) {
    val wallets = pairedWallets(navController = navController, holder = holder, listVersion = listVersion)
    AddressBookScreen(
        entries = wallets.flatMap { it.addressBook }.distinctBy { it.id },
        offline = rememberSettingsOffline(),
        onEdit = { address -> navController.navigate(LumioRoute.addressHandoff(address, edit = true)) },
        onBack = { navController.popBackStack() },
        onLock = onLock,
    )
}
