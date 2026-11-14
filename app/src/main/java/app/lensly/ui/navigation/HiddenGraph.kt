package app.lensly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import app.lensly.app.RegistryHolder
import app.lensly.app.VaultHolder
import app.lensly.app.WalletDraft
import app.lensly.app.WalletDraftViewModel

/**
 * Lensly's hidden area: the shared gate, then the wallet and settings screens. The catalog loads as the owner unlocks, because Home
 * shows its counts. The wallet screens are in [walletRoutes].
 */
internal fun NavGraphBuilder.hiddenGraph(navController: NavHostController, holder: VaultHolder) {
    hiddenArea(navController, holder, home = HiddenRoute.HOME, warmUp = { RegistryHolder.get(it) }) {
        walletRoutes(navController, holder)
        settingsRoutes(navController, holder)
    }
}

/** The draft shared by every screen of the hidden graph. It is scoped to the graph, so rotation keeps it. */
@Composable
internal fun draftOf(navController: NavHostController, entry: NavBackStackEntry): WalletDraft {
    // Keyed on this screen's own entry, which stays the same through the exit fade. Leaving the hidden area pops the
    // graph, and resolving its entry again there would throw. Entering the hidden area again composes the screen
    // afresh, with a new entry, so the graph's new owner is picked up then.
    val owner = remember(entry) { navController.getBackStackEntry(LenslyRoute.HIDDEN) }
    return viewModel<WalletDraftViewModel>(viewModelStoreOwner = owner).draft
}
