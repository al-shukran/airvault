package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import app.lensly.core.vault.VaultSession
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.data.config.DisplaySettings
import app.lumio.data.scan.ScanEvent
import app.lumio.home.HomeFormat
import app.lumio.network.NetworkMode
import app.lumio.network.NetworkModeStore
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.ui.receive.ReceiveScreen
import app.lumio.ui.settings.WalletsSettings
import app.lumio.ui.wallets.AccountScreen
import app.lumio.ui.wallets.WalletRow
import app.lumio.ui.wallets.WalletScreen
import app.lumio.ui.wallets.WalletsScreen
import app.lumio.ui.wallets.chainCounts
import app.lumio.wallets.AccountChain
import app.lumio.wallets.WalletAccount
import app.lumio.wallets.WalletAccounts
import app.lumio.wallets.WalletsLoader
import app.lumio.wallets.WalletsView
import app.lumio.wallets.readOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The notice a read that did not answer leaves on the wallet screens. The last known balances stay on them. */
private const val REFRESH_NOTICE = "Some balances could not be refreshed. The last known balances are shown."

/** What the wallet screens show: the balances of the side, whether a read runs and its notice, the display settings, and a refresh. */
private class WalletsState(
    val view: WalletsView?,
    val refreshing: Boolean,
    val notice: String?,
    val display: DisplaySettings,
    val refresh: () -> Unit,
)

/**
 * The balances of the open vault on [mode]'s side. They show from the caches when the screen opens. With [refreshOnOpen] the networks are read then too:
 * the Ethereum, Algorand and Solana balances, and the Bitcoin scans. A scan that finishes adds its balance at once, and [refresh] reads them all again.
 */
@Composable
private fun rememberWalletsState(
    app: LumioApp,
    session: VaultSession,
    mode: NetworkMode,
    listVersion: Int,
    refreshOnOpen: Boolean,
): WalletsState {
    val fallback = remember { MutableStateFlow(DisplaySettings()) }
    val display by (app.lumioVault()?.display ?: fallback).collectAsState()
    var view by remember { mutableStateOf<WalletsView?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(session, mode) {
        app.scanQueue.events.collect { event ->
            when (event) {
                is ScanEvent.Finished -> readOrNull { WalletsLoader.read(app, session, mode) }?.let { view = it }
                is ScanEvent.Failed -> notice = REFRESH_NOTICE
                is ScanEvent.Progress -> Unit
            }
        }
    }
    // Every read runs in this one effect, so a new read cancels the one before it. A side, a pairing and a refresh each start one.
    LaunchedEffect(session, mode, listVersion, refreshTick) {
        view = readOrNull { WalletsLoader.read(app, session, mode) } ?: view
        if (refreshTick == 0 && !refreshOnOpen) return@LaunchedEffect
        refreshing = true
        notice = null
        try {
            val failed = WalletsLoader.refresh(app, session, mode)
            view = readOrNull { WalletsLoader.read(app, session, mode) } ?: view
            if (failed) notice = REFRESH_NOTICE
        } finally {
            refreshing = false
        }
    }
    return WalletsState(view, refreshing, notice, display) { if (!refreshing) refreshTick++ }
}

/** The Wallets start screen: one row per paired wallet, with its total in dollars or its count of accounts per chain. */
@Composable
internal fun WalletsRoute(
    navController: NavHostController,
    holder: VaultHolder,
    selection: NetworkSelection,
    listVersion: Int,
    onLock: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    // The side the owner chose is kept in the vault. It is read when the vault opens, so the app opens on the side it was left on.
    LaunchedEffect(session) {
        selection.mode = withContext(Dispatchers.IO) { NetworkModeStore.read(session) }
    }
    val state = rememberWalletsState(app, session, selection.mode, listVersion, refreshOnOpen = true)
    val view = state.view ?: return
    WalletsScreen(
        rows = view.wallets.map { wallet -> WalletRow(wallet.id, wallet.name, walletDetail(view, wallet.id, state.display)) },
        refreshing = state.refreshing,
        notice = state.notice,
        onWallet = { id -> navController.navigate(LumioRoute.wallet(id)) },
        onPair = { navController.navigate(LumioRoute.PAIR_SCAN) },
        onRefresh = state.refresh,
        onLock = onLock,
    )
}

/** One paired wallet: its accounts on the side [mode] chooses. Missing when the wallet is not in the open vault. */
@Composable
internal fun WalletRoute(navController: NavHostController, holder: VaultHolder, walletId: String, mode: NetworkMode, listVersion: Int) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val state = rememberWalletsState(app, session, mode, listVersion, refreshOnOpen = true)
    val view = state.view ?: return
    val wallet = view.wallets.firstOrNull { it.id == walletId }
    if (wallet == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    WalletScreen(
        walletName = wallet.name,
        mode = mode,
        accounts = view.accountsOf(walletId),
        totalUsd = view.usd(walletId),
        showTotal = state.display.showTotal,
        showBalances = state.display.showAssets,
        refreshing = state.refreshing,
        notice = state.notice,
        onAccount = { account -> navController.navigate(LumioRoute.account(walletId, account.chain, account.index)) },
        onRefresh = state.refresh,
        onBack = { navController.popBackStack() },
    )
}

/**
 * One account of a wallet on the side [mode] chooses: its chain, address and balance, with the Send and Receive actions. It reads the caches only, so
 * opening it reads no network. Missing when the account is not in the open vault.
 */
@Composable
internal fun AccountRoute(
    navController: NavHostController,
    holder: VaultHolder,
    walletId: String,
    chain: AccountChain,
    index: Int,
    mode: NetworkMode,
    listVersion: Int,
) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val state = rememberWalletsState(app, session, mode, listVersion, refreshOnOpen = false)
    val view = state.view ?: return
    val account = view.accountsOf(walletId).firstOrNull { it.chain == chain && it.index == index }
    if (account == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    AccountScreen(
        walletName = view.wallets.firstOrNull { it.id == walletId }?.name.orEmpty(),
        account = account,
        showBalance = state.display.showAssets,
        onSend = { sendRoute(account, mode)?.let { navController.navigate(it) } },
        onReceive = { navController.navigate(LumioRoute.receive(walletId, chain, index)) },
        onBack = { navController.popBackStack() },
    )
}

/** The state of [ReceiveRoute]: the account with the name of its wallet, or missing when the vault has no such account. */
private sealed interface ReceiveLoad {
    data object Missing : ReceiveLoad

    class Ready(val walletName: String, val account: WalletAccount) : ReceiveLoad
}

/** The page that receives to one account: its address as the owner can copy it, and the same address as a QR code. */
@Composable
internal fun ReceiveRoute(
    navController: NavHostController,
    holder: VaultHolder,
    walletId: String,
    chain: AccountChain,
    index: Int,
    mode: NetworkMode,
) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val load by produceState<ReceiveLoad?>(initialValue = null, session, mode, walletId, chain, index) {
        val open = session
        value = WalletsLoader.account(app, open, mode, walletId, chain, index)
            ?.let { (name, account) -> ReceiveLoad.Ready(name, account) }
            ?: ReceiveLoad.Missing
    }
    when (val current = load) {
        null -> return
        ReceiveLoad.Missing -> {
            LaunchedEffect(Unit) { navController.popBackStack() }
            return
        }
        is ReceiveLoad.Ready -> ReceiveScreen(
            walletName = current.walletName,
            detail = receiveDetail(current.account),
            address = current.account.address,
            bitcoin = current.account.chain == AccountChain.BITCOIN,
            onBack = { navController.popBackStack() },
        )
    }
}

/** The line under the name of a receive: what the address is, its chain and network, and for Ethereum the address number. */
private fun receiveDetail(account: WalletAccount): String = when (account.chain) {
    AccountChain.ETHEREUM -> "Ethereum and every EVM network, address ${account.index + 1}"
    AccountChain.BITCOIN -> "${account.script.orEmpty().replaceFirstChar { it.uppercaseChar() }} on ${account.networkName}"
    AccountChain.SOLANA, AccountChain.ALGORAND -> account.networkName
}

/**
 * The route of a Send from [account] on [mode]'s side, or null when the chain has no Send yet. An Ethereum send starts on the side's primary network,
 * and a Bitcoin send opens its own form.
 */
internal fun sendRoute(account: WalletAccount, mode: NetworkMode): String? = when (account.chain) {
    AccountChain.ETHEREUM -> LumioRoute.send(account.walletId, account.index, WalletAccounts.primaryEvmChainId(mode))
    AccountChain.BITCOIN -> LumioRoute.BTC_SEND
    AccountChain.SOLANA -> LumioRoute.solanaSend(account.walletId, account.index)
    AccountChain.ALGORAND -> null
}

/**
 * The line under a wallet's name on the Wallets list: its total in dollars when the owner shows totals and a price is known, else the count of its
 * accounts per chain.
 */
private fun walletDetail(view: WalletsView, walletId: String, display: DisplaySettings): String {
    val usd = if (display.showTotal) view.usd(walletId) else null
    return usd?.let { HomeFormat.usd(it) } ?: chainCounts(view.accountsOf(walletId))
}

/**
 * The paired wallets of the open vault, read again when [listVersion] changes. Empty while the vault is locked, and the gate takes over then.
 */
@Composable
internal fun pairedWallets(navController: NavHostController, holder: VaultHolder, listVersion: Int): List<PairedWallet> {
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    val wallets by produceState<List<PairedWallet>>(initialValue = emptyList(), session, listVersion) {
        value = if (session == null) emptyList() else onVault(emptyList()) { PairedWallets.list(session) }
    }
    return wallets
}

/** The Wallets page of Settings: the pairing of a wallet from Lensly, and the paired wallets with deletion. A deletion is saved, then the lists read again. */
@Composable
internal fun WalletsSettingsRoute(navController: NavHostController, holder: VaultHolder, listVersion: Int, onChanged: () -> Unit) {
    val session = holder.openSession()
    val scope = rememberCoroutineScope()
    val wallets = pairedWallets(navController = navController, holder = holder, listVersion = listVersion)
    WalletsSettings(
        wallets = wallets,
        onPair = { navController.navigate(LumioRoute.PAIR_SCAN) },
        onDelete = { wallet ->
            if (session != null) {
                scope.launch {
                    onVault(Unit) { PairedWallets.delete(session, wallet.id) }
                    onChanged()
                }
            }
        },
    )
}
