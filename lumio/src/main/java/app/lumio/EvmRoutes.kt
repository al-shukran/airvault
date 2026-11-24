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
import app.lumio.data.config.withOrderWithin
import app.lumio.data.provider.ProviderCatalog
import app.lumio.evm.ChainRpcs
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmEdits
import app.lumio.evm.EvmRpc
import app.lumio.evm.EvmSettings
import app.lumio.evm.EvmSettingsStore
import app.lumio.ui.settings.EvmSettingsSection
import app.lumio.ui.settings.NetworkPage
import app.lumio.ui.settings.ProviderRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/** The settings and the chains they list, read from the open vault. */
private class SettingsState(val settings: EvmSettings, val chains: List<EvmChain>)

/** The chains Lumio reads: every registry network and custom chain, in the order the catalog gives. */
private fun catalogOf(app: LumioApp, settings: EvmSettings): List<EvmChain> = EvmCatalog.chains(app.registry, settings)

/**
 * The Ethereum section of the settings: how many addresses each wallet shows, which chains are read, the RPC address of each chain, and the
 * custom chains. Each change is written to the vault when it is valid. A change that needs the endpoint waits for it, and the section says
 * why it failed.
 */
@Composable
internal fun EvmSettingsRoute(navController: NavHostController, holder: VaultHolder) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val state by produceState<SettingsState?>(initialValue = null, session, version) {
        value = if (session == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val settings = EvmSettingsStore.read(session)
                    SettingsState(settings, catalogOf(app, settings))
                }.getOrNull()
            }
        }
    }
    val current = state ?: return

    // Runs [transform] on the stored settings off the main thread, writes the result, and reports the owner's words, or null on success.
    fun change(transform: (EvmSettings, EvmRpc) -> EvmSettings, onDone: (String?) -> Unit = { notice = it }) {
        if (session == null) {
            onDone("The vault is locked.")
            return
        }
        if (busy) {
            onDone("Wait for the last change to finish.")
            return
        }
        busy = true
        scope.launch {
            // A refused change is not written, so the stored settings, and an RPC address that did not check, stay as they were.
            val error = withContext(Dispatchers.IO) { EvmSettingsStore.change(session, app.evmRpc, transform) }
            busy = false
            if (error == null) version++
            onDone(error)
        }
    }

    EvmSettingsSection(
        settings = current.settings,
        chains = current.chains,
        busy = busy,
        notice = notice,
        onAddressCount = { count -> change({ s, _ -> EvmEdits.setAddressCount(s, count) }) },
        onEnabled = { chainId, on -> change({ s, _ -> EvmEdits.setEnabled(s, chainId, on) }) },
        onRemoveCustom = { chainId -> change({ s, _ -> EvmEdits.removeCustom(s, chainId) }) },
        onNetwork = { chainId -> navController.navigate(LumioRoute.network(chainId)) },
        onAddNetwork = { navController.navigate(LumioRoute.SETTINGS_ADD_NETWORK) },
        onTokens = { chainId -> navController.navigate(LumioRoute.tokens(chainId)) },
    )
}

/** What the Network page shows of one chain: the settings, the chain, and the data providers that serve it in the order they are asked. */
private class NetworkState(val settings: EvmSettings, val chain: EvmChain, val providers: List<ProviderRow>)

/**
 * The Network page (B4) of chain [chainId]: its built-in RPC address, the custom addresses the owner set with the one in use, and the order of the data
 * providers that serve the chain. A change of address is checked against the endpoint before it is saved, and a refusal shows under the field, with the
 * stored addresses kept as they were.
 */
@Composable
internal fun EvmNetworkRoute(navController: NavHostController, holder: VaultHolder, chainId: Long, onLock: () -> Unit) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    val scope = rememberCoroutineScope()
    val vault = app.lumioVault()
    val dataIds = remember { ProviderCatalog.dataProviders().map { it.id } }
    var version by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val state by produceState<NetworkState?>(initialValue = null, session, vault, version) {
        value = if (session == null || vault == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val settings = EvmSettingsStore.read(session)
                    val chain = catalogOf(app, settings).firstOrNull { it.chainId == chainId }
                    chain?.let { NetworkState(settings, it, readChainProviders(app, vault, it)) }
                }.getOrNull()
            }
        }
    }
    val current = state ?: return
    val writable = vault ?: return

    fun change(transform: (EvmSettings, EvmRpc) -> EvmSettings) {
        if (session == null || busy) return
        busy = true
        notice = null
        scope.launch {
            val error = withContext(Dispatchers.IO) { EvmSettingsStore.change(session, app.evmRpc, transform) }
            busy = false
            notice = error
            if (error == null) version++
        }
    }

    // The order is written to the data providers' settings, for every chain, so the other chains keep the places they hold.
    fun reorderProviders(order: List<String>) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { writable.updateConfigs { saved -> withOrderWithin(ProviderCatalog.withDefaults(saved), dataIds, order) } }
            }
            version++
        }
    }

    val rpcs = current.settings.rpc[chainId] ?: ChainRpcs()
    NetworkPage(
        chainName = current.chain.name,
        chainLine = "Chain ${current.chain.chainId} · ${current.chain.symbol}${if (current.chain.testnet) " · Testnet" else ""}",
        custom = rpcs.custom,
        active = rpcs.active,
        providers = current.providers,
        busy = busy,
        notice = notice,
        offline = rememberSettingsOffline(),
        onUseBuiltIn = { change { s, _ -> EvmEdits.resetRpc(s, chainId) } },
        onUseCustom = { url -> change { s, _ -> EvmEdits.useRpc(s, chainId, url) } },
        onRemoveCustom = { url -> change { s, _ -> EvmEdits.removeRpc(s, chainId, url) } },
        onAddCustom = { raw -> change { s, rpc -> EvmEdits.setRpc(s, chainId, raw, rpc) } },
        onReorder = ::reorderProviders,
        onBack = { navController.popBackStack() },
        onLock = onLock,
    )
}
