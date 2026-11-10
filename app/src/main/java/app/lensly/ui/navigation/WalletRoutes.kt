package app.lensly.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import app.lensly.BuildConfig
import app.lensly.app.VaultHolder
import app.lensly.app.WalletMeta
import app.lensly.app.WalletStore
import app.lensly.core.vault.VaultSession
import app.lensly.ui.home.SoonScreen
import app.lensly.ui.home.WalletHomeScreen
import app.lensly.ui.wallet.WalletStartScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The wallet routes of the hidden graph. Restore and create are placeholders until their slices land. */
internal fun NavGraphBuilder.walletRoutes(navController: NavHostController, holder: VaultHolder) {
    composable(HiddenRoute.HOME) { HiddenBackdrop { HomeRoute(navController, holder) } }
    composable(HiddenRoute.WALLET_START) {
        HiddenBackdrop {
            WalletStartScreen(
                onCreate = { navController.navigate(HiddenRoute.SOON) },
                onRestore = { navController.navigate(HiddenRoute.SOON) },
            )
        }
    }
    composable(HiddenRoute.SOON) {
        HiddenBackdrop { SoonScreen(onBack = { navController.popBackStack() }) }
    }
}

@Composable
private fun HomeRoute(navController: NavHostController, holder: VaultHolder) {
    val draft = draftOf(navController)
    val session = rememberOpenSession(navController, holder) ?: return
    val debugNotice = remember { debugKeystoreNotice(holder) }
    var wallets by remember { mutableStateOf<List<WalletMeta>>(emptyList()) }

    // The draft only lives while a create or restore flow runs. Arriving here ends the flow.
    LaunchedEffect(Unit) { draft.wipe() }
    LaunchedEffect(session) {
        whileUnlocked(navController, holder) {
            wallets = withContext(Dispatchers.IO) { WalletStore(session).list() }
        }
    }

    BackHandler { leaveHidden(navController, holder) }
    WalletHomeScreen(
        wallets = wallets,
        onOpen = {},
        onAdd = { navController.navigate(HiddenRoute.WALLET_START) },
        onLock = { leaveHidden(navController, holder) },
        debugNotice = debugNotice,
    )
}

/**
 * The open session, or null while locked. If the vault is locked while a hidden screen is showing, the gate
 * takes over again.
 */
@Composable
private fun rememberOpenSession(navController: NavHostController, holder: VaultHolder): VaultSession? {
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(HiddenRoute.GATE)
    }
    return session
}

/** Runs [block]. If the vault was locked while it ran, the owner is returned to the camera. Cancellation passes through. */
private suspend fun whileUnlocked(
    navController: NavHostController,
    holder: VaultHolder,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalStateException) {
        // A closed session throws IllegalStateException, and so does a damaged vault.
        leaveHidden(navController, holder)
    }
}

/** A warning chip in debug builds when the keystore is software-only, as on the emulator. Release builds never show it. */
private fun debugKeystoreNotice(holder: VaultHolder): String? =
    if (BuildConfig.DEBUG && !holder.vault.deviceSecurity().hardwareBacked) "Software keystore" else null
