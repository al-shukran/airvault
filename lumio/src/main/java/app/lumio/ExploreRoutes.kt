package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.core.registry.Registry
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmSettings
import app.lumio.evm.EvmSettingsStore
import app.lumio.network.NetworkMode
import app.lumio.ui.explore.ExploreScreen
import app.lumio.ui.explore.chainRows
import app.lumio.ui.kit.LumioTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Explore tab: the chains the owner's accounts sit on, the networks on the side, and the data providers, read the way the Data providers page reads
 * them. The providers are read once, when the tab opens.
 */
@Composable
internal fun ExploreRoute(navController: NavHostController, holder: VaultHolder, selection: NetworkSelection, listVersion: Int, onLock: () -> Unit) {
    val app = LocalContext.current.applicationContext as LumioApp
    val vault = app.lumioVault()
    val session = holder.openSession()
    val lists by produceState<ProviderLists?>(initialValue = null, vault) {
        if (vault != null) value = withContext(Dispatchers.IO) { readLists(app, vault) }
    }
    val evmSettings by produceState<EvmSettings?>(initialValue = null, session) {
        if (session != null) value = withContext(Dispatchers.IO) { EvmSettingsStore.read(session) }
    }
    val sideRead = rememberSideRead(session, selection)
    val view = rememberWalletsBoard(app, selection.mode, listVersion, ready = sideRead)?.view
    TabRoute(tab = LumioTab.EXPLORE, navController = navController, holder = holder, selection = selection, listVersion = listVersion, onLock = onLock) {
        // Nothing shows until the owner's side is read, so no chain row and no count of the default side appears first.
        if (!sideRead) return@TabRoute
        val current = lists ?: return@TabRoute
        val settings = evmSettings ?: return@TabRoute
        ExploreScreen(
            chains = view?.let { shown -> chainRows(shown.wallets.flatMap { wallet -> shown.accountsOf(wallet.id) }) }.orEmpty(),
            networks = networksOn(app.registry, settings, selection.mode),
            providers = current.data + current.prices,
            onChains = { navController.navigate(LumioRoute.SETTINGS_NETWORKS) },
            onProviders = { navController.navigate(LumioRoute.DATA_PROVIDERS) },
            onProvider = { id -> navController.navigate(LumioRoute.provider(id)) },
        )
    }
}

/** The networks of [mode] that the owner has switched on, custom chains included: the count the Explore tab shows as All N. */
internal fun networksOn(registry: Registry, settings: EvmSettings, mode: NetworkMode): Int =
    EvmCatalog.chains(registry, settings).count { it.chainId in settings.enabled && mode.holds(it) }
