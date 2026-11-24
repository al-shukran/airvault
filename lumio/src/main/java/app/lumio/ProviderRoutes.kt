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
import app.lumio.data.config.withApiKey
import app.lumio.data.provider.ProviderCatalog
import app.lumio.ui.settings.ProviderPage
import app.lumio.ui.settings.providerName
import app.lumio.ui.settings.providerStatusNote
import app.lumio.ui.settings.providerStatusWord
import app.lumio.ui.settings.providerTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many characters of a saved key the page shows, so the owner can tell which key is saved without seeing it. */
private const val KEY_TAIL = 4

/**
 * The Provider page (B5) of the provider [id]: its status, its tags and its API key. The status is read the way the Data providers page reads it, once
 * when the page opens, and again after a key is saved or removed. A key is written to the vault the same way the Data providers page writes it.
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
    val lists by produceState<ProviderLists?>(initialValue = null, vault, version) {
        value = withContext(Dispatchers.IO) { readLists(app, vault) }
    }
    val current = lists ?: return
    val row = (current.data + current.prices + current.rpc).firstOrNull { it.id == id } ?: return
    val traits = remember(id) {
        (ProviderCatalog.dataProviders() + ProviderCatalog.priceProviders() + ProviderCatalog.rpcProviders(BuildConfig.DEBUG))
            .firstOrNull { it.id == id }?.traits.orEmpty()
    }
    ProviderPage(
        name = providerName(id),
        status = providerStatusWord(row.status),
        statusNote = providerStatusNote(row.status),
        tags = providerTags(id, traits),
        keyed = row.acceptsKey,
        savedTail = row.apiKey?.takeIf { it.isNotBlank() }?.takeLast(KEY_TAIL),
        offline = rememberSettingsOffline(),
        onSaveKey = { key ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { vault.updateConfigs { saved -> withApiKey(ProviderCatalog.withDefaults(saved), id, key) } }
                }
                version++
            }
        },
        onBack = { navController.popBackStack() },
        onLock = onLock,
    )
}
