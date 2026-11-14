package app.lensly.ui.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import app.lensly.app.RegistryHolder
import app.lensly.app.VaultHolder
import app.lensly.ui.settings.ApprovedContractsScreen
import app.lensly.ui.settings.Catalog
import app.lensly.ui.settings.NetworksScreen
import app.lensly.ui.settings.TokensScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Networks, Tokens and Approved contracts lists, in the hidden graph, from Settings. */
internal fun NavGraphBuilder.catalogRoutes(navController: NavHostController, holder: VaultHolder) {
    composable(HiddenRoute.NETWORKS) { HiddenBackdrop { NetworksRoute(navController, holder) } }
    composable(HiddenRoute.TOKENS) { HiddenBackdrop { TokensRoute(navController, holder) } }
    composable(HiddenRoute.APPROVED_CONTRACTS) { HiddenBackdrop { ApprovedContractsRoute(navController, holder) } }
}

/** The networks, tokens and whitelist the catalog holds. The first read loads the assets, so it runs off the main thread. */
internal suspend fun readCatalog(context: Context): Catalog = withContext(Dispatchers.IO) {
    RegistryHolder.get(context).let {
        Catalog(
            networks = it.networks(),
            tokens = it.tokens(),
            whitelist = it.whitelistEntries(),
            denylist = it.denylistEntries(),
            wallets = it.walletEntries(),
            installedUpdateUnverified = it.installedUpdateUnverified,
        )
    }
}

@Composable
private fun ApprovedContractsRoute(navController: NavHostController, holder: VaultHolder) {
    rememberOpenSession(navController, holder) ?: return
    val catalog = rememberCatalog() ?: return
    ApprovedContractsScreen(
        entries = catalog.whitelist,
        networks = catalog.networks,
        onUpdateCatalog = { navController.navigate(HiddenRoute.CATALOG) },
        onBack = { navController.popBackStack() },
        onLock = { leaveHidden(navController, holder) },
    )
}

@Composable
private fun NetworksRoute(navController: NavHostController, holder: VaultHolder) {
    rememberOpenSession(navController, holder) ?: return
    val catalog = rememberCatalog() ?: return
    NetworksScreen(
        networks = catalog.networks,
        tokens = catalog.tokens,
        onBack = { navController.popBackStack() },
        onLock = { leaveHidden(navController, holder) },
    )
}

@Composable
private fun TokensRoute(navController: NavHostController, holder: VaultHolder) {
    rememberOpenSession(navController, holder) ?: return
    val catalog = rememberCatalog() ?: return
    TokensScreen(
        networks = catalog.networks,
        tokens = catalog.tokens,
        onBack = { navController.popBackStack() },
        onLock = { leaveHidden(navController, holder) },
    )
}

/** The catalog once it is read, and null until then. */
@Composable
private fun rememberCatalog(): Catalog? {
    val context = LocalContext.current
    return produceState<Catalog?>(initialValue = null, context) { value = readCatalog(context) }.value
}
