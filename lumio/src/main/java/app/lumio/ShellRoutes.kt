package app.lumio

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.airvault.core.model.AccountId
import app.lensly.app.VaultHolder
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.data.net.Networks
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmEdits
import app.lumio.evm.EvmSettingsStore
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallet
import app.lumio.ui.kit.LumioTab
import app.lumio.ui.network.PanelWallet
import app.lumio.ui.network.TestNetworksScreen
import app.lumio.ui.network.testNetworkSentence
import app.lumio.ui.settings.walletSummary
import app.lumio.ui.shell.LumioPageScreen
import app.lumio.ui.shell.LumioRootScreen
import app.lumio.ui.shell.offlineBand
import app.lumio.ui.shell.rememberOnline
import app.lumio.wallets.WalletAccounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The test networks that have no switch: one each on Bitcoin, Solana and Algorand, which Lumio reads on every build. */
private const val FIXED_TEST_NETWORKS = 3

/**
 * The root of a tab, with the shell around [content]: the root top bar, the offline band, the tab bar, and the profile panel, whose wallets and count of
 * test networks it reads here. The side the panel chooses is kept in the vault by [NetworkSelection]. A tab switch replaces the tab that is open.
 */
@Composable
internal fun TabRoute(
    tab: LumioTab,
    navController: NavHostController,
    holder: VaultHolder,
    selection: NetworkSelection,
    listVersion: Int,
    onLock: () -> Unit,
    title: String = tab.label,
    content: @Composable ColumnScope.() -> Unit,
) {
    val app = LocalContext.current.applicationContext as LumioApp
    val online = rememberOnline()
    val wallets = pairedWallets(navController, holder, listVersion)
    val active = wallets.active(selection) { it.id }
    val networks = testNetworkSummary(app, holder, listVersion)
    val primary = primaryNetwork(app, holder, selection.mode, listVersion)
    LumioRootScreen(
        tab = tab,
        title = title,
        mode = selection.mode,
        onMode = { selection.choose(it) },
        panelWallets = wallets.map { wallet -> PanelWallet(id = wallet.id, name = wallet.name, detail = walletSummary(wallet)) },
        activeWalletId = active?.id,
        onWallet = { selection.walletId = it },
        testNetworks = networks.count,
        networkSentence = testNetworkSentence(networks.evmNames),
        scanNote = scanNoteOf(active, primary?.offName),
        scanReady = primary != null,
        offline = if (online) null else offlineBand(app.lumioVault()?.wallets?.updatedAtMs),
        onTab = { navController.openTab(it) },
        onScan = {
            if (primary != null) active?.let { scanToPayRoute(it, selection.mode, primaryOn = primary.offName == null) }?.let { navController.navigate(it) }
        },
        onTestNetworks = { navController.navigate(LumioRoute.TEST_NETWORKS) },
        onPair = { navController.navigate(LumioRoute.PAIR_SCAN) },
        onLock = onLock,
        search = { onClose ->
            SearchRoute(navController = navController, holder = holder, selection = selection, listVersion = listVersion, onClose = onClose)
        },
        content = content,
    )
}

/**
 * Opens [tab]'s root in place of the tab that is open. The tab bar shows only on the roots, so a switch always starts from a root, and the start
 * tab stays below the others so that Back from another tab returns to Accounts.
 */
internal fun NavHostController.openTab(tab: LumioTab) {
    navigate(tab.route()) {
        popUpTo(LumioRoute.WALLETS) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** The route of [tab]'s root. */
private fun LumioTab.route(): String = when (this) {
    LumioTab.HOME -> LumioRoute.HOME
    LumioTab.ACCOUNTS -> LumioRoute.WALLETS
    LumioTab.PORTFOLIO -> LumioRoute.PORTFOLIO
    LumioTab.EXPLORE -> LumioRoute.EXPLORE
    LumioTab.SETTINGS -> LumioRoute.SETTINGS
}


/**
 * The test networks page: the EVM test networks with a switch each, and the networks that have no switch. The settings are read on the first frame,
 * and each switch is written at once. A refused change says why under the intro.
 */
@Composable
internal fun TestNetworksRoute(navController: NavHostController, holder: VaultHolder, onLock: () -> Unit) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    var settings by remember(session) { mutableStateOf(EvmSettingsStore.read(session)) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val online = rememberOnline()
    LumioPageScreen(
        title = "Test Networks",
        backLabel = navController.backLabel(),
        offline = if (online) null else offlineBand(app.lumioVault()?.wallets?.updatedAtMs),
        onBack = { navController.popBackStack() },
        onLock = onLock,
    ) {
        TestNetworksScreen(
            chains = EvmCatalog.chains(app.registry, settings).filter { it.testnet },
            enabled = settings.enabled,
            notice = notice,
            onEnabled = { chainId, on ->
                scope.launch {
                    notice = withContext(Dispatchers.IO) {
                        EvmSettingsStore.change(session, app.evmRpc) { current, _ -> EvmEdits.setEnabled(current, chainId, on) }
                    }
                    settings = withContext(Dispatchers.IO) { EvmSettingsStore.read(session) }
                }
            },
            onAddCustom = { navController.navigate(LumioRoute.SETTINGS_ADD_NETWORK) },
        )
    }
}

/**
 * The name that a page's back arrow gives: the screen that Back returns to, so TalkBack says where the arrow goes. A page with no tab or known page
 * beneath it says "the previous screen".
 */
internal fun NavHostController.backLabel(): String = backLabelOf(previousBackStackEntry?.destination?.route)

/** The name of the screen at [route], the one that Back returns to. A route with no name here says "the previous screen". */
internal fun backLabelOf(route: String?): String = when (route) {
    LumioRoute.HOME -> "Home"
    LumioRoute.WALLETS -> "Accounts"
    LumioRoute.PORTFOLIO -> "Portfolio"
    LumioRoute.EXPLORE -> "Explore"
    LumioRoute.SETTINGS -> "Settings"
    LumioRoute.SETTINGS_NETWORKS -> "Networks"
    LumioRoute.TEST_NETWORKS -> "Test Networks"
    LumioRoute.DATA_PROVIDERS -> "Data Providers"
    LumioRoute.WALLET -> "Wallet"
    else -> "the previous screen"
}

/** The test networks in use: how many there are, and the names of the EVM ones that are switched on. */
private data class TestNetworkSummary(val count: Int, val evmNames: List<String>)

/** The side's primary network, read from the settings: [offName] is its name while it is switched off, else null. Scan to Pay needs that network on. */
private class PrimaryNetwork(val offName: String?)

/** The side's primary network once the settings are read, and null before that, so Scan to Pay stays inert until the switch is known. */
@Composable
private fun primaryNetwork(app: LumioApp, holder: VaultHolder, mode: NetworkMode, listVersion: Int): PrimaryNetwork? {
    val session = holder.openSession()
    val primary by produceState<PrimaryNetwork?>(initialValue = null, session, mode, listVersion) {
        value = if (session == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val settings = EvmSettingsStore.read(session)
                val chainId = WalletAccounts.primaryEvmChainId(mode)
                val chain = EvmCatalog.chains(app.registry, settings).firstOrNull { it.chainId == chainId }
                PrimaryNetwork(offName = if (chainId in settings.enabled) null else chain?.name)
            }
        }
    }
    return primary
}

/** The test networks in use: the EVM ones that are switched on, and the networks that have no switch. */
@Composable
private fun testNetworkSummary(app: LumioApp, holder: VaultHolder, listVersion: Int): TestNetworkSummary {
    val session = holder.openSession()
    val summary by produceState(initialValue = TestNetworkSummary(FIXED_TEST_NETWORKS, emptyList()), session, listVersion) {
        value = if (session == null) {
            TestNetworkSummary(FIXED_TEST_NETWORKS, emptyList())
        } else {
            withContext(Dispatchers.IO) {
                val settings = EvmSettingsStore.read(session)
                val on = EvmCatalog.chains(app.registry, settings).filter { it.testnet && it.chainId in settings.enabled }
                TestNetworkSummary(on.size + FIXED_TEST_NETWORKS, on.map { it.name })
            }
        }
    }
    return summary
}

/** The wallet the shell shows: the one the profile panel chose, or the first paired wallet until the owner chooses one. */
internal fun <T> List<T>.active(selection: NetworkSelection, id: (T) -> String): T? = firstOrNull { id(it) == selection.walletId } ?: firstOrNull()

/**
 * The send that Scan to Pay opens: the wallet's first Ethereum account on the side's primary network, with the camera on for the recipient. A wallet
 * with no Ethereum account has no such send, and neither does a side whose primary network is switched off ([primaryOn] false). The panel says why.
 */
internal fun scanToPayRoute(wallet: PairedWallet, mode: NetworkMode, primaryOn: Boolean): String? {
    val address = wallet.evmAddresses.firstOrNull() ?: return null
    if (!primaryOn) return null
    val chainId = WalletAccounts.primaryEvmChainId(mode)
    return LumioRoute.send(wallet.id, AccountId.of(Networks.evm(chainId), address), chainId, scan = true)
}

/**
 * The note Scan to Pay shows in place of its description when it cannot open a send, or null when it can: the wallet must be paired, it must have an
 * Ethereum account, and the side's primary network [primaryOff] must be on.
 */
internal fun scanNoteOf(wallet: PairedWallet?, primaryOff: String?): String? = when {
    wallet == null -> "Pair a wallet to scan to pay"
    wallet.evmAddresses.isEmpty() -> "This wallet has no Ethereum account to scan to pay"
    primaryOff != null -> "Turn on $primaryOff to scan to pay"
    else -> null
}
