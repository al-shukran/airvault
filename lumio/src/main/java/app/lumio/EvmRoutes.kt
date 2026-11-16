package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.evm.CustomChainInput
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmEdits
import app.lumio.evm.EvmRpc
import app.lumio.evm.EvmSettings
import app.lumio.evm.EvmSettingsStore
import app.lumio.ui.settings.EvmSettingsSection
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
        onSetRpc = { chainId, raw, onDone -> change({ s, rpc -> EvmEdits.setRpc(s, chainId, raw, rpc) }, onDone) },
        onResetRpc = { chainId -> change({ s, _ -> EvmEdits.resetRpc(s, chainId) }) },
        onRemoveCustom = { chainId -> change({ s, _ -> EvmEdits.removeCustom(s, chainId) }) },
        onAddCustom = { input: CustomChainInput, onDone ->
            val builtIn = app.registry.networks().map { it.chainId }.toSet()
            change({ s, rpc -> EvmEdits.addCustom(s, builtIn, input, rpc) }, onDone)
        },
        onTokens = { chainId -> navController.navigate(LumioRoute.tokens(chainId)) },
    )
}
