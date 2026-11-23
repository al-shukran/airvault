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
import app.lumio.data.config.PreferRpcStore
import app.lumio.data.config.ProviderConfig
import app.lumio.data.config.configOf
import app.lumio.data.config.orderedIds
import app.lumio.data.config.withApiKey
import app.lumio.data.config.withEnabled
import app.lumio.data.config.withMoved
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderCatalog
import app.lumio.data.provider.Trait
import app.lumio.data.sched.ProviderHealth
import app.lumio.ui.settings.DataProvidersScreen
import app.lumio.ui.settings.ProviderRow
import app.lumio.ui.settings.providerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How often the screen reads the schedulers again, so a cooldown or an open circuit counts down on screen. */
private const val STATUS_POLL_MS = 2_000L

/** The three groups of rows, read in one pass. */
internal class ProviderLists(
    val data: List<ProviderRow>,
    val prices: List<ProviderRow>,
    val rpc: List<ProviderRow>,
    val preferRpc: Boolean,
)

/**
 * The Data providers screen in the Hidden Area. It reads the saved settings and each provider's health from the schedulers, and it writes each change
 * to the open vault's provider settings. The routers read those settings on their next call, so a change takes effect at once.
 */
@Composable
internal fun DataProvidersRoute(navController: NavHostController, holder: VaultHolder) {
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
    val dataIds = remember { ProviderCatalog.dataProviders().map { it.id } }
    val priceIds = remember { ProviderCatalog.priceProviders().map { it.id } }
    val rpcIds = remember { ProviderCatalog.rpcProviders(BuildConfig.DEBUG).map { it.id } }
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
        onBack = { navController.popBackStack() },
        // Each write starts from the defaults-filled settings, so a price provider never saves a default as a plain 0.
        onEnabled = { id, on -> change { writable.updateConfigs { saved -> withEnabled(ProviderCatalog.withDefaults(saved), id, on) } } },
        onMove = { id, up ->
            val group = when (id) {
                in dataIds -> dataIds
                in priceIds -> priceIds
                else -> rpcIds
            }
            change { writable.updateConfigs { saved -> withMoved(ProviderCatalog.withDefaults(saved), group, id, up) } }
        },
        onApiKey = { id, key -> change { writable.updateConfigs { saved -> withApiKey(ProviderCatalog.withDefaults(saved), id, key) } } },
        preferRpc = current.preferRpc,
        onPreferRpc = { on -> change { PreferRpcStore.write(writable.session, on) } },
    )
}

/** Reads the three groups of rows: each provider's settings, and its status from whichever scheduler holds it. */
internal suspend fun readLists(app: LumioApp, vault: LumioVault): ProviderLists {
    val configs = ProviderCatalog.withDefaults(vault.configs())
    val evm = app.evmReadsFor(vault.session)
    val healthOf: suspend (String) -> ProviderHealth? = { id ->
        app.scheduler.statusOf(id) ?: app.priceScheduler.statusOf(id) ?: evm.statusOf(id)
    }
    return ProviderLists(
        data = rowsOf(ProviderCatalog.dataProviders(), configs, healthOf),
        prices = rowsOf(ProviderCatalog.priceProviders(), configs, healthOf),
        rpc = rowsOf(ProviderCatalog.rpcProviders(BuildConfig.DEBUG), configs, healthOf),
        preferRpc = PreferRpcStore.read(vault.session),
    )
}

/** The rows of one group, in the order the settings list them. */
private suspend fun rowsOf(
    providers: List<Provider>,
    configs: Map<String, ProviderConfig>,
    healthOf: suspend (String) -> ProviderHealth?,
): List<ProviderRow> {
    val byId = providers.associateBy { it.id }
    val ordered = orderedIds(providers.map { it.id }, configs)
    return ordered.mapIndexed { index, id ->
        val provider = checkNotNull(byId[id])
        val config = configOf(configs, id)
        ProviderRow(
            id = id,
            mevProtected = Trait.MEV_PROTECTED in provider.traits,
            acceptsKey = id in ProviderCatalog.KEY_LISTED,
            enabled = config.enabled,
            apiKey = config.apiKey,
            status = providerStatus(provider.traits, config, healthOf(id)),
            canMoveUp = index > 0,
            canMoveDown = index < ordered.lastIndex,
        )
    }
}
