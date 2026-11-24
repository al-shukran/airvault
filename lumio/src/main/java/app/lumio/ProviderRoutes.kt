package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.data.config.withApiKey
import app.lumio.data.config.withEnabled
import app.lumio.data.provider.ProviderCatalog
import app.lumio.data.provider.Trait
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmSettingsStore
import app.lumio.ui.settings.ProviderPage
import app.lumio.ui.settings.ProviderRow
import app.lumio.ui.settings.providerName
import app.lumio.ui.settings.providerStatusNote
import app.lumio.ui.settings.providerStatusWord
import app.lumio.ui.settings.providerTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many characters of a saved key the page shows, so the owner can tell which key is saved without seeing it. */
private const val KEY_TAIL = 4

private const val KEY_SAVE_FAILED = "The key could not be saved. Try again."

/** What the Provider page shows of one provider: its row, its tags, and the names of the networks it serves in catalogue order. */
private class ProviderState(val row: ProviderRow, val tags: List<String>, val networks: List<String>, val keyRequired: Boolean)

/**
 * The Provider page (B5) of the provider [id]: its status, its switch, its tags, the networks it serves and its API key. The page reads the provider the
 * way the Data providers page does, once when it opens and again after a change. Each change is written to the open vault's provider settings.
 */
@Composable
internal fun ProviderRoute(navController: NavHostController, holder: VaultHolder, id: String, onLock: () -> Unit) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val vault = app.lumioVault() ?: return
    val state by produceState<ProviderState?>(initialValue = null, vault, version) {
        value = withContext(Dispatchers.IO) { readProvider(app, vault, id) }
    }
    val current = state ?: return
    var keyError by remember { mutableStateOf<String?>(null) }

    // Runs one write off the main thread, then reads the provider again. [onFailure] runs when the write throws.
    fun change(onFailure: () -> Unit = {}, write: () -> Unit) {
        scope.launch {
            val failed = withContext(Dispatchers.IO) { runCatching(write).isFailure }
            if (failed) onFailure()
            version++
        }
    }

    ProviderPage(
        name = providerName(id),
        status = providerStatusWord(current.row.status),
        statusNote = providerStatusNote(current.row.status),
        enabled = current.row.enabled,
        onEnabled = { on -> change { vault.updateConfigs { saved -> withEnabled(ProviderCatalog.withDefaults(saved), id, on) } } },
        tags = current.tags,
        networks = current.networks,
        keyed = current.row.acceptsKey,
        keyRequired = current.keyRequired,
        savedTail = current.row.apiKey?.takeIf { it.isNotBlank() }?.takeLast(KEY_TAIL),
        keyError = keyError,
        offline = rememberSettingsOffline(),
        onSaveKey = { key ->
            keyError = null
            change(onFailure = { keyError = KEY_SAVE_FAILED }) {
                vault.updateConfigs { saved -> withApiKey(ProviderCatalog.withDefaults(saved), id, key) }
            }
        },
        onBack = { navController.popBackStack() },
        onLock = onLock,
    )
}

/** The state of provider [id], or null when no provider has that ID. Read off the main thread. */
private suspend fun readProvider(app: LumioApp, vault: LumioVault, id: String): ProviderState? {
    val lists = readLists(app, vault)
    val row = (lists.data + lists.prices + lists.rpc).firstOrNull { it.id == id } ?: return null
    val provider = (ProviderCatalog.dataProviders() + ProviderCatalog.priceProviders() + ProviderCatalog.rpcProviders(BuildConfig.DEBUG))
        .firstOrNull { it.id == id } ?: return null
    val chains = EvmCatalog.chains(app.registry, EvmSettingsStore.read(vault.session))
    return ProviderState(
        row = row,
        tags = providerTags(id, provider.traits),
        networks = ProviderCatalog.evmChainsOf(provider, chains).map { it.name },
        keyRequired = Trait.KEY_REQUIRED in provider.traits,
    )
}
