package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lumio.ui.explore.ExploreScreen
import app.lumio.ui.kit.LumioTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Explore tab: the chains and the data providers, read the way the Data providers page reads them. The providers are read once, when the tab opens.
 */
@Composable
internal fun ExploreRoute(navController: NavHostController, holder: VaultHolder, selection: NetworkSelection, listVersion: Int, onLock: () -> Unit) {
    val app = LocalContext.current.applicationContext as LumioApp
    val vault = app.lumioVault()
    val lists by produceState<ProviderLists?>(initialValue = null, vault) {
        if (vault != null) value = withContext(Dispatchers.IO) { readLists(app, vault) }
    }
    TabRoute(tab = LumioTab.EXPLORE, navController = navController, holder = holder, selection = selection, listVersion = listVersion, onLock = onLock) {
        val current = lists ?: return@TabRoute
        ExploreScreen(
            providers = current.data + current.prices,
            onChains = { navController.navigate(LumioRoute.SETTINGS_NETWORKS) },
            onProviders = { navController.navigate(LumioRoute.DATA_PROVIDERS) },
            onProvider = { id -> navController.navigate(LumioRoute.provider(id)) },
        )
    }
}
