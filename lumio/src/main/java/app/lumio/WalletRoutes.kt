package app.lumio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.core.vault.VaultSession
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.data.config.DisplaySettings
import app.lumio.data.price.ChartRange
import app.lumio.data.price.PriceSeries
import app.lumio.network.NetworkMode
import app.lumio.network.NetworkModeStore
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.transactions.TxFeedItem
import app.lumio.transactions.TxSync
import app.lumio.transactions.TxSyncPlan
import app.lumio.ui.receive.ReceiveScreen
import app.lumio.ui.settings.WalletsSettings
import app.lumio.ui.wallets.AccountScreen
import app.lumio.ui.wallets.AssetRowModel
import app.lumio.ui.wallets.AssetRows
import app.lumio.ui.wallets.AssetScreen
import app.lumio.ui.wallets.OpenSheet
import app.lumio.ui.wallets.WalletLoadingScreen
import app.lumio.ui.wallets.WalletRow
import app.lumio.ui.wallets.WalletScreen
import app.lumio.ui.wallets.WalletSheet
import app.lumio.ui.wallets.WalletSheetHost
import app.lumio.ui.wallets.WalletSheets
import app.lumio.ui.wallets.WalletsScreen
import app.lumio.ui.wallets.summaryLine
import app.lumio.wallets.AccountChain
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
private fun rememberDisplay(app: LumioApp): DisplaySettings {
    val fallback = remember { MutableStateFlow(DisplaySettings()) }
    val display by (app.lumioVault()?.display ?: fallback).collectAsState()
    return display
}

/**
 * The balances of the open vault, which every wallet screen shares. Its side and list version are shown as they change, from the vault's caches only: the
 * network reads start on the Wallets home alone. Null while the vault is locked. [ready] holds the showing back until the side the owner chose is read.
 */
@Composable
private fun rememberWalletsBoard(app: LumioApp, mode: NetworkMode, listVersion: Int, ready: Boolean = true): WalletsBoard? {
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
    val sheets = app.lumioVault()?.sheets ?: return
    SideEffect { sheets.keepSide(selection.mode) }
    val display = rememberDisplay(app)
    val view = board.view
    Box(modifier = Modifier.fillMaxSize()) {
        WalletsScreen(
            rows = view?.let { shown -> shown.wallets.map { wallet -> walletRow(shown, wallet) } }.orEmpty(),
            loading = view == null,
            totalUsd = view?.let(::allWalletsUsd),
            showTotal = display.showTotal,
            refreshing = board.refreshing,
            refreshByPull = board.refreshByPull,
            updatedAtMs = board.updatedAtMs,
            notice = board.notice,
            onWallet = { id -> sheets.open(WalletSheet.Wallet(id)) },
            onPair = { navController.navigate(LumioRoute.PAIR_SCAN) },
            onRefresh = { board.refresh(pulled = true) },
            onSettings = { navController.navigate(LumioRoute.SETTINGS) { launchSingleTop = true } },
        )
        WalletSheetHost(sheets) { opened ->
            when (val sheet = opened.sheet) {
                is WalletSheet.Wallet -> WalletSheetScreen(opened, sheets, sheet.walletId, board, display, selection.mode, app)
                is WalletSheet.Account -> AccountSheetScreen(opened, sheets, navController, session, sheet, board, display, selection.mode, app)
            }
        }
    }
}

/**
 * The Wallet sheet: one paired wallet and its accounts on the side [mode] chooses. The balances come from the open vault's shared board, so the sheet shows
 * them on its first frame. It closes when the wallet is not on the board.
 */
@Composable
private fun WalletSheetScreen(
    opened: OpenSheet,
    sheets: WalletSheets,
    walletId: String,
    board: WalletsBoard,
    display: DisplaySettings,
    mode: NetworkMode,
    app: LumioApp,
) {
    val view = board.view
    val wallet = view?.wallets?.firstOrNull { it.id == walletId }
    if (view == null || wallet == null) {
        LaunchedEffect(Unit) { sheets.closeSheet(opened) }
        return
    }
    WalletScreen(
        walletName = wallet.name,
        fingerprint = wallet.fingerprint.toInt(),
        mode = mode,
        accounts = view.accountsOf(walletId),
        tokenCounts = board.tokenCounts,
        network = app.registry::network,
        totalUsd = view.usd(walletId),
        showTotal = display.showTotal,
        showBalances = display.showAssets,
        refreshing = board.refreshing,
        updatedAtMs = board.updatedAtMs,
        notice = board.notice,
        onAccount = { account -> sheets.open(WalletSheet.Account(walletId, account.chain, account.index)) },
        onBack = { sheets.closeSheet(opened) },
    )
}

/**
 * The Account sheet: one account of a wallet on [mode]'s side. Its balance comes from the shared board, and its other reads from the account's kept reads. Its
 * Send, Receive, asset and transaction pages are pushed over the Wallets home as before. It closes when the account is not on the board.
 */
@Composable
private fun AccountSheetScreen(
    opened: OpenSheet,
    sheets: WalletSheets,
    navController: NavHostController,
    session: VaultSession,
    sheet: WalletSheet.Account,
    board: WalletsBoard,
    display: DisplaySettings,
    mode: NetworkMode,
    app: LumioApp,
) {
    val walletId = sheet.walletId
    val chain = sheet.chain
    val index = sheet.index
    val view = board.view
    val account = view?.accountsOf(walletId)?.firstOrNull { it.chain == chain && it.index == index }
    if (view == null || account == null) {
        LaunchedEffect(Unit) { sheets.closeSheet(opened) }
        return
    }
    val reads = remember(board, account, mode) { board.readsOf(account, mode) }
    val activity = rememberAccountActivity(app, session, mode, account, reads)
    AccountScreen(
        walletName = view.wallets.firstOrNull { it.id == walletId }?.name.orEmpty(),
        account = account,
        showBalance = display.showAssets,
        assets = rememberAssetRows(app, account, reads),
        loadingAssets = reads.tokens == null || reads.holdings == null,
        activity = activity.orEmpty(),
        loadingActivity = activity == null,
        refreshing = board.refreshing,
        notice = board.notice,
        onRefresh = { board.refresh() },
        onSend = { navController.navigate(sendRoute(account, mode)) },
        onReceive = { navController.navigate(LumioRoute.receive(walletId, chain, index)) },
        onAsset = { asset -> navController.navigate(LumioRoute.asset(walletId, chain, index, asset.assetKey)) },
        onAddAsset = if (chain == AccountChain.ALGORAND) {
            { navController.navigate(LumioRoute.algorandAddAsset(walletId, index)) }
        } else {
            null
        },
        onOpenTransaction = { item -> navController.navigate(LumioRoute.transaction(walletId, item.tx.account, item.tx.id)) },
        onBack = { sheets.closeSheet(opened) },
    )
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
    chain: AccountChain,
    index: Int,
    assetKey: String,
    mode: NetworkMode,
    listVersion: Int,
) {
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
    val account = view.accountsOf(walletId).firstOrNull { it.chain == chain && it.index == index }
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
    AssetScreen(
        asset = asset,
        showBalance = display.showAssets,
        chart = chart,
        activity = activity.orEmpty().filter { AssetRows.assetKeyOf(it.tx) == assetKey },
        onSend = { navController.navigate(sendRoute(account, mode)) },
        onReceive = { navController.navigate(LumioRoute.receive(walletId, chain, index)) },
        onOpenTransaction = { item -> navController.navigate(LumioRoute.transaction(walletId, item.tx.account, item.tx.id)) },
        onBack = { navController.popBackStack() },
    )
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
    val plan by produceState<TxSyncPlan?>(null, session, mode, account.walletId, account.chain, account.index) {
        value = withContext(Dispatchers.IO) { AccountReadsLoader.planOf(app, session, mode, account) }
    }
    // A count of the reads that have landed. Each read adds the Bitcoin addresses it turns up to the reads, which the board keeps for the next visit.
    val landed = remember(session, mode, account.walletId, account.chain, account.index) { MutableStateFlow(0) }
    val landedNow by landed.collectAsState()
    val owned by reads.addresses.collectAsState()
    LaunchedEffect(plan) {
        val current = plan ?: return@LaunchedEffect
        val open = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            TxSync.run(app, open, current, onStored = { landed.update { it + 1 } }, onAddress = { address -> reads.addresses.update { it + address } })
        }
    }
    val addresses = when (account.chain) {
        AccountChain.ETHEREUM -> setOf(account.address)
        AccountChain.BITCOIN -> owned
        AccountChain.SOLANA, AccountChain.ALGORAND -> emptySet()
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
            onlySend = "Only send ${current.account.networkName} assets here.",
            title = "Receive ${current.account.balance?.symbol ?: current.account.chain.label}",
            note = if (current.account.chain == AccountChain.SOLANA) "Tokens arrive at this same address." else null,
            onBack = { navController.popBackStack() },
        )
    }
}

/** The line under the name of a receive: what the address is, its chain and network, and for Ethereum the address number. */
private fun receiveDetail(account: WalletAccount): String = when (account.chain) {
    AccountChain.ETHEREUM -> "Ethereum and every EVM network, address ${account.index + 1}"
    AccountChain.BITCOIN -> "${account.script.orEmpty()} on ${account.networkName}"
    AccountChain.SOLANA, AccountChain.ALGORAND -> account.networkName
}

/**
 * The route of a Send from [account] on [mode]'s side. Every chain sends from its own account, with no picker: the send is bound to that account and
 * its network. An Ethereum send is on the side's primary network, the one the account shows.
 */
internal fun sendRoute(account: WalletAccount, mode: NetworkMode): String = when (account.chain) {
    AccountChain.ETHEREUM -> LumioRoute.send(account.walletId, account.index, WalletAccounts.primaryEvmChainId(mode))
    AccountChain.BITCOIN -> LumioRoute.btcSend(account.walletId, account.index)
    AccountChain.SOLANA -> LumioRoute.solanaSend(account.walletId, account.index)
    AccountChain.ALGORAND -> LumioRoute.algorandSend(account.walletId, account.index)
}

/** One paired wallet as a row of the Wallets list: its name, the fingerprint its orb is drawn from, its summary line and its dollar total. */
private fun walletRow(view: WalletsView, wallet: PairedWallet): WalletRow = WalletRow(
    id = wallet.id,
    name = wallet.name,
    fingerprint = wallet.fingerprint.toInt(),
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
