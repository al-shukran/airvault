package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.data.config.ProviderConfig
import app.lumio.data.config.configOf
import app.lumio.data.config.orderedIds
import app.lumio.data.config.withOrderWithin
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderCatalog
import app.lumio.data.sched.ProviderHealth
import app.lumio.evm.EvmChain
import app.lumio.ui.settings.DataProvidersScreen
import app.lumio.ui.settings.ProviderRow
import app.lumio.ui.settings.providerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How often the screen reads the schedulers again, so a cooldown or an open circuit counts down on screen. */
internal const val STATUS_POLL_MS = 2_000L

/** [read] now, and again every [everyMs], for as long as the flow is collected. */
internal fun <T> polled(everyMs: Long, read: suspend () -> T): Flow<T> = flow {
    while (true) {
        emit(read())
        delay(everyMs)
    }
}

/** The three groups of rows, read in one pass. */
internal class ProviderLists(
    val data: List<ProviderRow>,
    val prices: List<ProviderRow>,
    val rpc: List<ProviderRow>,
    val preferRpc: Boolean,
) {
    /** The providers the Data providers page lists: the number that Explore shows as "All N". */
    val listed: Int get() = data.size + prices.size + rpc.size
}

/**
 * The Data providers screen in the Hidden Area. It reads the saved settings and each provider's health from the schedulers, and it writes each change
 * to the open vault's provider settings. The routers read those settings on their next call, so a change takes effect at once.
 */
@Composable
internal fun DataProvidersRoute(navController: NavHostController, holder: VaultHolder, onLock: () -> Unit) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    val vault = app.lumioVault()
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val lists by produceState<ProviderLists?>(initialValue = null, vault, version) {
        if (vault == null) {
            value = null
            return@produceState
        }
        while (true) {
            value = withContext(Dispatchers.IO) { readLists(app, vault) }
            delay(STATUS_POLL_MS)
        }
    }
    val writes = remember { Mutex() }
    val current = lists ?: return
    val writable = vault ?: return

    // Runs one write off the main thread, after the writes made before it, then reads the lists again.
    fun change(write: () -> Unit) {
        scope.launch {
            writes.withLock { withContext(Dispatchers.IO) { runCatching(write) } }
            version++
        }
    }

    DataProvidersScreen(
        data = current.data,
        prices = current.prices,
        rpc = current.rpc,
        offline = rememberSettingsOffline(),
        onBack = { navController.popBackStack() },
        onLock = onLock,
        onOpen = { id -> navController.navigate(LumioRoute.provider(id)) },
        // Each write starts from the defaults-filled settings, so a price provider never saves a default as a plain 0.
        onReorder = { group, order -> change { writable.updateConfigs { saved -> withOrderWithin(ProviderCatalog.withDefaults(saved), group, order) } } },
        preferRpc = current.preferRpc,
        onPreferRpc = { on -> change { writable.savePreferRpc(on) } },
        backLabel = navController.backLabel(),
    )
}

/** Reads the three groups of rows: each provider's settings, and its status from whichever scheduler holds it. */
internal suspend fun readLists(app: LumioApp, vault: LumioVault): ProviderLists {
    val configs = ProviderCatalog.withDefaults(vault.configs())
    val healthOf = healthReader(app, vault)
    return ProviderLists(
        data = rowsOf(ProviderCatalog.dataProviders(), configs, healthOf),
        prices = rowsOf(ProviderCatalog.priceProviders(), configs, healthOf),
        rpc = rowsOf(ProviderCatalog.rpcProviders(BuildConfig.DEBUG), configs, healthOf),
        preferRpc = vault.preferRpc(),
    )
}

/** The data providers that serve [chain], in the order the settings list them, each with its status. */
internal suspend fun readChainProviders(app: LumioApp, vault: LumioVault, chain: EvmChain): List<ProviderRow> {
    val serving = ProviderCatalog.dataProviders().filter { ProviderCatalog.servesEvmChain(it, chain) }
    return rowsOf(serving, ProviderCatalog.withDefaults(vault.configs()), healthReader(app, vault))
}

/** What the schedulers say about a provider now: its status is read from whichever scheduler holds it. */
private suspend fun healthReader(app: LumioApp, vault: LumioVault): suspend (String) -> ProviderHealth? {
    val evm = app.evmReadsFor(vault.session)
    return { id -> app.scheduler.statusOf(id) ?: app.priceScheduler.statusOf(id) ?: evm.statusOf(id) }
}

/** The rows of one group, in the order the settings list them. */
private suspend fun rowsOf(
    providers: List<Provider>,
    configs: Map<String, ProviderConfig>,
    healthOf: suspend (String) -> ProviderHealth?,
): List<ProviderRow> {
    val byId = providers.associateBy { it.id }
    return orderedIds(providers.map { it.id }, configs).map { id ->
        val provider = checkNotNull(byId[id])
        val config = configOf(configs, id)
        ProviderRow(
            id = id,
            acceptsKey = id in ProviderCatalog.KEY_LISTED,
            enabled = config.enabled,
            apiKey = config.apiKey,
            status = providerStatus(provider.traits, config, healthOf(id)),
        )
    }
}
