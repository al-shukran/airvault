package app.lumio

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.leaveHidden
import app.lensly.ui.navigation.openHidden
import app.lumio.ui.kit.lumioComposable
import app.lumio.ui.settings.ChangePasswordSettingsPage

/**
 * The App Password change in Settings → Security. The page is Lumio's own Clear Air page. The change re-wraps the key of Lumio's own vault,
 * and Lensly's vault is not changed. The change runs only while the vault is open, as on every Lumio screen.
 */
internal fun NavGraphBuilder.changePasswordRoutes(navController: NavHostController, holder: VaultHolder, selection: NetworkSelection) {
    lumioComposable(LumioRoute.CHANGE_PASSWORD) {
        LumioBackdrop(selection) { LumioChangePasswordRoute(navController, holder) }
    }
}

/** The change of Lumio's App Password. A locked vault shows the gate, and a vault that cannot be changed shows the neutral screen. */
@Composable
private fun LumioChangePasswordRoute(navController: NavHostController, holder: VaultHolder) {
    if (!rememberGateSession(navController, holder)) return
    ChangePasswordSettingsPage(
        offline = rememberSettingsOffline(),
        onSubmit = { current, password, repeat -> changeLumioPassword(holder, current, password, repeat) },
        onChanged = { navController.popBackStack() },
        onLocked = { leaveHidden(navController, holder) },
        onUnavailable = { navController.openHidden(GateRoutes.UNAVAILABLE) },
        onBack = { navController.popBackStack() },
        onLock = { leaveHidden(navController, holder) },
    )
}
