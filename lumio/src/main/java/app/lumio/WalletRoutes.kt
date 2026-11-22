package app.lumio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.airvault.core.model.AccountId
import app.lensly.app.VaultHolder
import app.lensly.core.vault.VaultSession
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.data.config.DisplaySettings
import app.lumio.data.net.Networks
import app.lumio.data.price.ChartRange
import app.lumio.data.price.PriceSeries
import app.lumio.network.NetworkMode
import app.lumio.network.NetworkModeStore
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.transactions.TxFeedItem
import app.lumio.transactions.TxSync
import app.lumio.transactions.TxSyncPlan
import app.lumio.receive.ReceiveAnswers
import app.lumio.ui.kit.LumioTab
import app.lumio.ui.receive.ReceiveFlowSheet
import app.lumio.ui.receive.receiveNativeStartOf
import app.lumio.ui.receive.receiveStartOf
import app.lumio.ui.settings.WalletsSettings
import app.lumio.ui.shell.LumioPageScreen
import app.lumio.ui.shell.offlineBand
import app.lumio.ui.shell.rememberOnline
import app.lumio.ui.wallets.AccountScreen
import app.lumio.ui.wallets.AssetRowModel
import app.lumio.ui.wallets.AssetRows
import app.lumio.ui.wallets.AssetScreen
import app.lumio.ui.wallets.WalletLoadingContent
import app.lumio.ui.wallets.WalletLoadingScreen
import app.lumio.ui.wallets.WalletRow
import app.lumio.ui.wallets.WalletScreen
import app.lumio.ui.wallets.WalletsScreen
import app.lumio.ui.wallets.accountTitle
import app.lumio.ui.wallets.summaryLine
import app.lumio.wallets.AccountReads
import app.lumio.wallets.AccountReadsLoader
import app.lumio.wallets.WalletAccount
import app.lumio.wallets.WalletAccounts
import app.lumio.wallets.WalletsBoard
import app.lumio.wallets.WalletsLoader
import app.lumio.wallets.WalletsView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The display settings of the open vault: whether totals and balances show. */
@Composable
internal fun rememberDisplay(app: LumioApp): DisplaySettings {
    val fallback = remember { MutableStateFlow(DisplaySettings()) }
    val display by (app.lumioVault()?.display ?: fallback).collectAsState()
    return display
}

/**
 * The balances of the open vault, which every wallet screen shares. Its side and list version are shown as they change, from the vault's caches only: the
 * network reads start on the Wallets home alone. Null while the vault is locked. [ready] holds the showing back until the side the owner chose is read.
 */
@Composable
internal fun rememberWalletsBoard(app: LumioApp, mode: NetworkMode, listVersion: Int, ready: Boolean = true): WalletsBoard? {
    val board = app.lumioVault()?.wallets
    LaunchedEffect(board, mode, listVersion, ready) {
        if (ready) board?.showSide(mode, listVersion)
    }
    return board
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
    var sideRead by remember(session) { mutableStateOf(false) }
    LaunchedEffect(session) {
        selection.mode = withContext(Dispatchers.IO) { NetworkModeStore.read(session) }
        sideRead = true
    }
    val board = rememberWalletsBoard(app, selection.mode, listVersion, ready = sideRead) ?: return
    // The Wallets home is the one screen that reads the networks as it opens: once per unlock, after a return from the background, and after a change of side.
    LaunchedEffect(board, sideRead, board.refreshDue) {
        if (sideRead && board.refreshDue) board.refresh()
    }
    val display = rememberDisplay(app)
    val view = board.view
    TabRoute(tab = LumioTab.ACCOUNTS, navController = navController, holder = holder, selection = selection, listVersion = listVersion, onLock = onLock) {
        WalletsScreen(
            rows = view?.let { shown -> shown.wallets.map { wallet -> walletRow(shown, wallet) } }.orEmpty(),
            loading = view == null,
            totalUsd = view?.let(::allWalletsUsd),
            showTotal = display.showTotal,
            refreshing = board.refreshing,
            refreshByPull = board.refreshByPull,
            updatedAtMs = board.updatedAtMs,
            notice = board.notice,
            onWallet = { id -> navController.navigate(LumioRoute.wallet(id)) },
            onPair = { navController.navigate(LumioRoute.PAIR_SCAN) },
            onRefresh = { board.refresh(pulled = true) },
        )
    }
}

/**
 * One paired wallet's page: its accounts on the side [selection] chooses, under the shell's page top bar. The balances come from the open vault's shared
 * board, so the page shows them on its first frame. Back returns to the Accounts tab, and the page leaves when the wallet is not on the board.
 */
@Composable
internal fun WalletRoute(
    navController: NavHostController,
    holder: VaultHolder,
    walletId: String,
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
    val board = rememberWalletsBoard(app, selection.mode, listVersion) ?: return
    val display = rememberDisplay(app)
    val online = rememberOnline()
    val view = board.view
    val wallet = view?.wallets?.firstOrNull { it.id == walletId }
    if (view != null && wallet == null) LaunchedEffect(Unit) { navController.popBackStack() }
    LumioPageScreen(
        title = wallet?.name.orEmpty(),
        backLabel = navController.backLabel(),
        offline = if (online) null else offlineBand(board.updatedAtMs),
        onBack = { navController.popBackStack() },
        onLock = onLock,
    ) {
        if (view == null || wallet == null) {
            WalletLoadingContent()
        } else {
            WalletScreen(
                mode = selection.mode,
                accounts = view.accountsOf(walletId),
                tokenCounts = board.tokenCounts,
                tokenUsd = board.tokenUsd,
                totalUsd = view.usd(walletId),
                usdOf = view::usd,
                showTotal = display.showTotal,
                showBalances = display.showAssets,
                refreshing = board.refreshing,
                refreshByPull = board.refreshByPull,
                updatedAtMs = board.updatedAtMs,
                notice = board.notice,
                pairedAtMs = wallet.createdAtMs,
                onAccount = { account -> navController.navigate(LumioRoute.account(walletId, account.id)) },
                onRefresh = { board.refresh(pulled = true) },
            )
        }
    }
}

/**
 * One account's page on the side [selection] chooses: its balance, its network and address, its assets and its activity, under the shell's page top
 * bar. Its balance comes from the shared board, and its other reads from the account's kept reads. Its Send, Receive, asset and transaction pages are
 * pushed over it. Back returns to the wallet, and the page leaves when the account is not on the board.
 */
@Composable
internal fun AccountRoute(
    navController: NavHostController,
    holder: VaultHolder,
    walletId: String,
    accountId: AccountId,
    selection: NetworkSelection,
    listVersion: Int,
    onLock: () -> Unit,
) {
    var receive by remember { mutableStateOf<ReceiveAnswers?>(null) }
    var receiveOpen by remember { mutableStateOf(false) }
    var receiveOpening by remember { mutableIntStateOf(0) }
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val mode = selection.mode
    val board = rememberWalletsBoard(app, mode, listVersion) ?: return
    val display = rememberDisplay(app)
    val online = rememberOnline()
    val view = board.view
    val account = view?.accountsOf(walletId)?.firstOrNull { it.id == accountId }
    if (view != null && account == null) LaunchedEffect(Unit) { navController.popBackStack() }
    LumioPageScreen(
        title = account?.let(::accountTitle).orEmpty(),
        backLabel = navController.backLabel(),
        offline = if (online) null else offlineBand(board.updatedAtMs),
        onBack = { navController.popBackStack() },
        onLock = onLock,
        sheet = {
            receive?.let { start ->
                ReceiveFlowSheet(
                    visible = receiveOpen,
                    opening = receiveOpening,
                    start = start,
                    assets = emptyList(),
                    onClose = { receiveOpen = false },
                )
            }
        },
    ) {
        if (view == null || account == null) {
            WalletLoadingContent()
        } else {
            val reads = remember(board, account, mode) { board.readsOf(account, mode) }
            val activity = rememberAccountActivity(app, session, mode, account, reads)
            val assetRows = rememberAssetRows(app, account, reads)
            AccountScreen(
                walletName = view.wallets.firstOrNull { it.id == walletId }?.name.orEmpty(),
                account = account,
                showBalance = display.showAssets,
                assets = assetRows,
                loadingAssets = reads.tokens == null || reads.holdings == null,
                activity = activity.orEmpty(),
                loadingActivity = activity == null,
                refreshing = board.refreshing,
                refreshByPull = board.refreshByPull,
                updatedAtMs = board.updatedAtMs,
                notice = board.notice,
                onRefresh = { board.refresh(pulled = true) },
                onSend = { navController.navigate(sendRoute(account, mode)) },
                onReceive = {
                    account.coin?.let { coin ->
                        val icon = AssetRows.nativeIcon(coin.network, coin.symbol, app.registry::network)
                        receive = receiveNativeStartOf(account, coin, icon, view.wallets)
                        receiveOpening++
                        receiveOpen = true
                    }
                },
                onAsset = { asset -> navController.navigate(LumioRoute.asset(walletId, account.id, asset.assetKey)) },
                onAddAsset = if (account.namespace == Networks.ALGORAND) {
                    { navController.navigate(LumioRoute.algorandAddAsset(walletId, account.id)) }
                } else {
                    null
                },
                onOpenTransaction = { item -> navController.navigate(LumioRoute.transaction(walletId, item.tx.account, item.tx.id)) },
                online = online,
            )
        }
    }
}

/**
 * One asset of an account on [mode]'s side: its amount and value, its price chart when it is priced, and its activity. The asset is found among the
 * account's rows by [assetKey]. A row that the account's reads have not landed yet keeps the screen on its placeholder, and a row that is still missing
 * once they have leaves the screen. Send and Receive act on the account.
 */
@Composable
internal fun AssetRoute(
    navController: NavHostController,
    holder: VaultHolder,
    walletId: String,
    accountId: AccountId,
    assetKey: String,
    mode: NetworkMode,
    listVersion: Int,
) {
    var receive by remember { mutableStateOf<ReceiveAnswers?>(null) }
    var receiveOpen by remember { mutableStateOf(false) }
    var receiveOpening by remember { mutableIntStateOf(0) }
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val board = rememberWalletsBoard(app, mode, listVersion) ?: return
    val display = rememberDisplay(app)
    val view = board.view
    if (view == null) {
        WalletLoadingScreen(onBack = { navController.popBackStack() })
        return
    }
    val account = view.accountsOf(walletId).firstOrNull { it.id == accountId }
    if (account == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val reads = remember(board, account, mode) { board.readsOf(account, mode) }
    val asset = rememberAssetRows(app, account, reads).firstOrNull { it.assetKey == assetKey }
    if (asset == null) {
        if (reads.tokens == null || reads.holdings == null) {
            WalletLoadingScreen(onBack = { navController.popBackStack() })
        } else {
            LaunchedEffect(Unit) { navController.popBackStack() }
        }
        return
    }
    val assetId = asset.assetId
    val chart by produceState<PriceSeries?>(assetId?.let(board::chartOf), assetId) {
        if (assetId == null) {
            value = null
            return@produceState
        }
        val series = withContext(Dispatchers.IO) { runCatching { app.prices.chart(assetId, ChartRange.D7) }.getOrNull() }
        if (series != null) board.putChart(assetId, series)
        value = series
    }
    val activity = rememberAccountActivity(app, session, mode, account, reads)
    Box(modifier = Modifier.fillMaxSize()) {
        AssetScreen(
            asset = asset,
            showBalance = display.showAssets,
            chart = chart,
            activity = activity.orEmpty().filter { AssetRows.assetKeyOf(it.tx) == assetKey },
            onSend = { navController.navigate(sendRoute(account, mode)) },
            onReceive = {
                receive = receiveStartOf(asset, account, view.wallets)
                receiveOpening++
                receiveOpen = true
            },
            onOpenTransaction = { item -> navController.navigate(LumioRoute.transaction(walletId, item.tx.account, item.tx.id)) },
            onBack = { navController.popBackStack() },
        )
        receive?.let { start ->
            ReceiveFlowSheet(
                visible = receiveOpen,
                opening = receiveOpening,
                start = start,
                assets = emptyList(),
                onClose = { receiveOpen = false },
            )
        }
    }
}

/**
 * The assets of [account] as its rows: the native coin, the ERC-20 tokens, and the SPL tokens or Algorand assets, from what [reads] holds. A priced row
 * shows its value in dollars once its price is read, and until then it shows its amount alone.
 */
@Composable
private fun rememberAssetRows(app: LumioApp, account: WalletAccount, reads: AccountReads): List<AssetRowModel> {
    val tokens = reads.tokens.orEmpty()
    val holdings = reads.holdings.orEmpty()
    val prices = reads.prices
    return remember(account, tokens, holdings, prices) {
        AssetRows.of(account, tokens, holdings, prices, app.registry::network, app.registry::token)
    }
}

/**
 * The newest rows of [account] on [mode]'s side, from [reads] and then as they land. The board reads the rows as the vault opens, so they show on the first
 * frame. The account's network reads run as the screen opens: the Ethereum address on each enabled network, or the Bitcoin account's scan. A Bitcoin
 * account's rows are those of every address that the scan turns up, and each read that lands shows its rows. Solana and Algorand have no history provider
 * yet, so they show none. Null until the first page is read.
 */
@Composable
private fun rememberAccountActivity(
    app: LumioApp,
    session: VaultSession,
    mode: NetworkMode,
    account: WalletAccount,
    reads: AccountReads,
): List<TxFeedItem>? {
    val vault = app.lumioVault()
    val plan by produceState<TxSyncPlan?>(null, session, mode, account.walletId, account.id) {
        value = withContext(Dispatchers.IO) { AccountReadsLoader.planOf(app, session, mode, account) }
    }
    // A count of the reads that have landed. Each read adds the Bitcoin addresses it turns up to the reads, which the board keeps for the next visit.
    val landed = remember(session, mode, account.walletId, account.id) { MutableStateFlow(0) }
    val landedNow by landed.collectAsState()
    val owned by reads.addresses.collectAsState()
    LaunchedEffect(plan) {
        val current = plan ?: return@LaunchedEffect
        val open = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            TxSync.run(app, open, current, onStored = { landed.update { it + 1 } }, onAddress = { address -> reads.addresses.update { it + address } })
        }
    }
    val addresses = when (account.namespace) {
        Networks.EIP155 -> setOf(account.address)
        Networks.BIP122 -> owned
        else -> emptySet()
    }
    val items by produceState(reads.activity, plan, addresses, landedNow) {
        val current = plan ?: return@produceState
        val open = vault ?: return@produceState
        // A Bitcoin account with no address known yet has no rows to show: its rows wait for its scan, rather than show none.
        if (addresses.isEmpty()) return@produceState
        val page = withContext(Dispatchers.IO) { AccountReadsLoader.activityOf(app, open, addresses, current.networks) }
        reads.activity = page
        value = page
    }
    return items
}

/**
 * The route of a Send from [account] on [mode]'s side. Every chain sends from its own account, with no picker: the send is bound to that account and
 * its network. An Ethereum send is on the side's primary network, the one the account shows.
 */
internal fun sendRoute(account: WalletAccount, mode: NetworkMode): String = when (account.namespace) {
    Networks.EIP155 -> LumioRoute.send(account.walletId, account.id, WalletAccounts.primaryEvmChainId(mode))
    Networks.BIP122 -> LumioRoute.btcSend(account.walletId, account.id)
    Networks.SOLANA -> LumioRoute.solanaSend(account.walletId, account.id)
    Networks.ALGORAND -> LumioRoute.algorandSend(account.walletId, account.id)
    else -> error("Lumio sends from no account on ${account.namespace}")
}

/** One paired wallet as a row of the Wallets list: its name, its summary line and its dollar total. */
private fun walletRow(view: WalletsView, wallet: PairedWallet): WalletRow = WalletRow(
    id = wallet.id,
    name = wallet.name,
    summary = summaryLine(view.accountsOf(wallet.id)),
    usd = view.usd(wallet.id),
)

/** The dollar total of every wallet on the side, or null when none of its balances has a price. */
private fun allWalletsUsd(view: WalletsView): Double? = view.wallets.mapNotNull { view.usd(it.id) }.takeIf { it.isNotEmpty() }?.sum()

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
