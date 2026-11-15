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
import app.lensly.core.vault.VaultSession
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.evm.AddressRow
import app.lumio.evm.BalanceCache
import app.lumio.evm.ChainFailure
import app.lumio.evm.CustomChainInput
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmEdits
import app.lumio.evm.EvmRpc
import app.lumio.evm.EvmSettings
import app.lumio.evm.EvmSettingsStore
import app.lumio.evm.Portfolio
import app.lumio.evm.PortfolioTotals
import app.lumio.evm.RpcFailure
import app.lumio.evm.Totals
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.ui.portfolio.WalletScreen
import app.lumio.ui.settings.EvmSettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A paired wallet's portfolio as the screen shows it: its enabled chains, one row per shown address, and the totals of each side. */
internal class PortfolioView(
    val wallet: PairedWallet,
    val chains: List<EvmChain>,
    val rows: List<AddressRow>,
    val totals: Totals,
)

/** The portfolio state of [WalletRoute]: [Missing] when the wallet is not in the open vault, otherwise the view. */
private sealed interface PortfolioState {
    data object Missing : PortfolioState
    class Ready(val view: PortfolioView) : PortfolioState
}

/** The settings and the chains they list, read from the open vault. */
private class SettingsState(val settings: EvmSettings, val chains: List<EvmChain>)

/** The chains Lumio reads: every registry network and custom chain, in the order the catalog gives. */
private fun catalogOf(app: LumioApp, settings: EvmSettings): List<EvmChain> = EvmCatalog.chains(app.registry, settings)

/**
 * The portfolio of [walletId] from the open vault and its balance cache. Runs off the main thread. It reads no network: the cached reads
 * are shown with the time each was read.
 */
private fun loadPortfolio(app: LumioApp, session: VaultSession, walletId: String): PortfolioState {
    val wallet = PairedWallets.list(session).firstOrNull { it.id == walletId } ?: return PortfolioState.Missing
    val settings = EvmSettingsStore.read(session)
    val chains = catalogOf(app, settings).filter { it.chainId in settings.enabled }
    val cache = BalanceCache.read(session)
    val account = wallet.evm
    val rows = if (account == null) emptyList() else Portfolio.rows(wallet.id, account, settings.addressCount, chains, cache)
    val totals = PortfolioTotals.of(Portfolio.holdings(listOf(wallet), settings.addressCount, chains, cache))
    return PortfolioState.Ready(PortfolioView(wallet, chains, rows, totals))
}

/**
 * Reads [wallet]'s enabled chains from the RPC addresses of the vault's settings and stores the reads in the cache. Returns the chains
 * that failed. The vault reads and writes block, so the caller runs it off the main thread.
 */
private suspend fun refreshWallet(app: LumioApp, session: VaultSession, wallet: PairedWallet): List<ChainFailure> {
    val settings = EvmSettingsStore.read(session)
    val chains = catalogOf(app, settings).filter { it.chainId in settings.enabled }
    return Portfolio.refresh(session, listOf(wallet), settings, chains, app.evmRpc)[wallet.id].orEmpty()
}

/**
 * The portfolio of one paired wallet. It reads the balances from the cache when it opens, then reads them again from each enabled chain's
 * RPC address. A chain that fails keeps its cached read and is named on the screen.
 */
@Composable
internal fun WalletRoute(navController: NavHostController, holder: VaultHolder, walletId: String) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var failures by remember { mutableStateOf<List<ChainFailure>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    val state by produceState<PortfolioState?>(initialValue = null, session, walletId, version) {
        value = if (session == null) {
            null
        } else {
            withContext(Dispatchers.IO) { runCatching { loadPortfolio(app, session, walletId) }.getOrDefault(PortfolioState.Missing) }
        }
    }
    val ready = (state as? PortfolioState.Ready)?.view
    if (state is PortfolioState.Missing) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }

    fun refresh(wallet: PairedWallet) {
        if (session == null || refreshing || wallet.evm == null) return
        refreshing = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { refreshWallet(app, session, wallet) }.getOrNull() }
            failures = outcome.orEmpty()
            problem = if (outcome == null) "The balances could not be saved. Try again." else null
            refreshing = false
            version++
        }
    }

    // A read happens when the screen opens, once for each wallet, and then on the Refresh button.
    LaunchedEffect(ready?.wallet?.id) {
        ready?.let { refresh(it.wallet) }
    }
    if (ready == null) return
    WalletScreen(
        walletName = ready.wallet.name,
        hasEvm = ready.wallet.evm != null,
        chains = ready.chains,
        rows = ready.rows,
        totals = ready.totals,
        failures = failures,
        problem = problem,
        refreshing = refreshing,
        onRefresh = { refresh(ready.wallet) },
        onBack = { navController.popBackStack() },
    )
}

/**
 * The EVM settings: how many addresses each wallet shows, which chains are read, the RPC address of each chain, and the custom chains.
 * Each change is written to the vault when it is valid. A change that needs the endpoint waits for it, and the screen says why it failed.
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
            val error = withContext(Dispatchers.IO) {
                try {
                    EvmSettingsStore.write(session, transform(EvmSettingsStore.read(session), app.evmRpc))
                    null
                } catch (e: IllegalArgumentException) {
                    e.message ?: "That change is not valid."
                } catch (e: RpcFailure) {
                    e.message ?: "The endpoint did not answer."
                } catch (e: Exception) {
                    "The change could not be saved. Try again."
                }
            }
            busy = false
            if (error == null) version++
            onDone(error)
        }
    }

    EvmSettingsScreen(
        settings = current.settings,
        chains = current.chains,
        busy = busy,
        notice = notice,
        onBack = { navController.popBackStack() },
        onAddressCount = { count -> change({ s, _ -> EvmEdits.setAddressCount(s, count) }) },
        onEnabled = { chainId, on -> change({ s, _ -> EvmEdits.setEnabled(s, chainId, on) }) },
        onSetRpc = { chainId, raw, onDone -> change({ s, rpc -> EvmEdits.setRpc(s, chainId, raw, rpc) }, onDone) },
        onResetRpc = { chainId -> change({ s, _ -> EvmEdits.resetRpc(s, chainId) }) },
        onRemoveCustom = { chainId -> change({ s, _ -> EvmEdits.removeCustom(s, chainId) }) },
        onAddCustom = { input: CustomChainInput, onDone ->
            val builtIn = app.registry.networks().map { it.chainId }.toSet()
            change({ s, rpc -> EvmEdits.addCustom(s, builtIn, input, rpc) }, onDone)
        },
    )
}
