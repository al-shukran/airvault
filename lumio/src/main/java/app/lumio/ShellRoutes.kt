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
import app.lensly.app.VaultHolder
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmEdits
import app.lumio.evm.EvmSettingsStore
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallet
import app.lumio.ui.kit.LumioTab
import app.lumio.ui.network.PanelWallet
import app.lumio.ui.network.TestNetworksScreen
import app.lumio.ui.shell.LumioPageScreen
import app.lumio.ui.shell.LumioRootScreen
import app.lumio.ui.shell.offlineBand
import app.lumio.ui.shell.rememberOnline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    LumioRootScreen(
        tab = tab,
        title = title,
        mode = selection.mode,
        onMode = { selection.choose(it) },
        panelWallets = pairedWallets(navController, holder, listVersion).map { wallet ->
            PanelWallet(id = wallet.id, name = wallet.name, detail = walletDetail(wallet))
        },
        testNetworks = testNetworkCount(app, holder, listVersion),
        offline = if (online) null else offlineBand(app.lumioVault()?.wallets?.updatedAtMs),
        onTab = { navController.openTab(it) },
        onTestNetworks = { navController.navigate(LumioRoute.TEST_NETWORKS) },
        onWallet = { navController.navigate(LumioRoute.wallet(it)) },
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
internal fun NavHostController.backLabel(): String = when (previousBackStackEntry?.destination?.route) {
    LumioRoute.HOME -> "Home"
    LumioRoute.WALLETS -> "Accounts"
    LumioRoute.PORTFOLIO -> "Portfolio"
    LumioRoute.EXPLORE -> "Explore"
    LumioRoute.SETTINGS -> "Settings"
    LumioRoute.WALLET -> "Wallet"
    else -> "the previous screen"
}

/** The number of test networks in use: the EVM ones that are switched on, and the networks that have no switch. */
@Composable
private fun testNetworkCount(app: LumioApp, holder: VaultHolder, listVersion: Int): Int {
    val session = holder.openSession()
    val count by produceState(initialValue = FIXED_TEST_NETWORKS, session, listVersion) {
        value = if (session == null) {
            FIXED_TEST_NETWORKS
        } else {
            withContext(Dispatchers.IO) {
                val settings = EvmSettingsStore.read(session)
                EvmCatalog.chains(app.registry, settings).count { it.testnet && it.chainId in settings.enabled } + FIXED_TEST_NETWORKS
            }
        }
    }
    return count
}

/** A wallet's line in the profile panel: its account count and the day it was paired, such as "30 accounts · paired 21 Nov". */
private fun walletDetail(wallet: PairedWallet): String {
    val accounts = wallet.evmAccounts.size + wallet.bitcoin.size + wallet.solana.size + wallet.algorand.size
    val noun = if (accounts == 1) "account" else "accounts"
    val paired = SimpleDateFormat("d MMM", Locale.ENGLISH).format(Date(wallet.createdAtMs))
    return "$accounts $noun · paired $paired"
}
