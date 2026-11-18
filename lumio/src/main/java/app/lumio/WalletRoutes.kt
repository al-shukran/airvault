package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import app.lumio.chains.ChainHolding
import app.lumio.chains.ChainHoldings
import app.lumio.data.config.DisplaySettings
import app.lumio.data.price.ChartRange
import app.lumio.data.price.PriceSeries
import app.lumio.data.provider.algorand.ALGORAND_MAINNET
import app.lumio.data.provider.algorand.ALGORAND_TESTNET
import app.lumio.data.provider.solana.SOLANA_DEVNET
import app.lumio.data.provider.solana.SOLANA_MAINNET
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmSettingsStore
import app.lumio.network.NetworkMode
import app.lumio.network.NetworkModeStore
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.tokens.TokenBalanceCache
import app.lumio.tokens.TokenPortfolio
import app.lumio.transactions.TxFeedItem
import app.lumio.transactions.TxSync
import app.lumio.transactions.TxSyncPlan
import app.lumio.transactions.accountPage
import app.lumio.ui.receive.ReceiveScreen
import app.lumio.ui.settings.WalletsSettings
import app.lumio.ui.wallets.AccountScreen
import app.lumio.ui.wallets.AssetRowModel
import app.lumio.ui.wallets.AssetRows
import app.lumio.ui.wallets.AssetScreen
import app.lumio.ui.wallets.WalletLoadingScreen
import app.lumio.ui.wallets.WalletRow
import app.lumio.ui.wallets.WalletScreen
import app.lumio.ui.wallets.WalletsScreen
import app.lumio.ui.wallets.summaryLine
import app.lumio.wallets.AccountActivity
import app.lumio.wallets.AccountChain
import app.lumio.wallets.AccountReads
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

/** How many of an account's newest transactions its screen shows. */
private const val ACTIVITY_ROWS = 10

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
    val display = rememberDisplay(app)
    val view = board.view
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
        onSettings = { navController.navigate(LumioRoute.SETTINGS) { launchSingleTop = true } },
    )
}

/**
 * One paired wallet: its accounts on the side [mode] chooses. The balances come from the open vault's shared board, so the screen shows them on its
 * first frame and starts no read of its own. Missing when the wallet is not in the open vault.
 */
@Composable
internal fun WalletRoute(navController: NavHostController, holder: VaultHolder, walletId: String, mode: NetworkMode, listVersion: Int) {
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
    val wallet = view.wallets.firstOrNull { it.id == walletId }
    if (wallet == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val accounts = view.accountsOf(walletId)
    WalletScreen(
        walletId = walletId,
        walletName = wallet.name,
        fingerprint = wallet.fingerprint.toInt(),
        mode = mode,
        accounts = accounts,
        tokenCounts = board.tokenCounts,
        network = app.registry::network,
        totalUsd = view.usd(walletId),
        showTotal = display.showTotal,
        showBalances = display.showAssets,
        refreshing = board.refreshing,
        refreshByPull = board.refreshByPull,
        updatedAtMs = board.updatedAtMs,
        notice = board.notice,
        onAccount = { account -> navController.navigate(LumioRoute.account(walletId, account.chain, account.index)) },
        onRefresh = { board.refresh(pulled = true) },
        onBack = { navController.popBackStack() },
    )
}

/**
 * One account of a wallet on the side [mode] chooses: its chain, address and balance, its tokens, its holdings, its recent activity, and the Send and Receive
 * actions. The balance comes from the shared board, and the other parts from the account's reads, which the board keeps: a revisit shows them at once, and
 * a first visit reserves their rows until they land. Missing when the account is not in the open vault.
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
    AccountReadsEffects(app, session, mode, account, reads)
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
        onAddAsset = if (account.chain == AccountChain.ALGORAND) {
            { navController.navigate(LumioRoute.algorandAddAsset(walletId, index)) }
        } else {
            null
        },
        onOpenTransaction = { item -> navController.navigate(LumioRoute.transaction(walletId, item.tx.account, item.tx.id)) },
        onBack = { navController.popBackStack() },
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
    AccountReadsEffects(app, session, mode, account, reads)
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
 * Reads the tokens, the holdings of other assets and the prices of [account] into [reads] as its screen opens. Each part keeps the value it had until the new
 * one lands, so an account that opens again shows what it read before at once. A read that fails leaves its part empty, and the rest of the screen stays as
 * it is.
 */
@Composable
private fun AccountReadsEffects(app: LumioApp, session: VaultSession, mode: NetworkMode, account: WalletAccount, reads: AccountReads) {
    LaunchedEffect(session, mode, account.walletId, account.chain, account.index) {
        reads.tokens = if (account.chain != AccountChain.ETHEREUM) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                val settings = EvmSettingsStore.read(session)
                val chains = EvmCatalog.chains(app.registry, settings).filter { it.chainId in settings.enabled && mode.holds(it) }
                TokenPortfolio.holdingsAt(account.walletId, account.address, chains, app.tokensFor(session), TokenBalanceCache.read(session))
                    .filter { it.amount.signum() > 0 }
            }
        }
        reads.holdings = when (account.chain) {
            AccountChain.SOLANA, AccountChain.ALGORAND -> withContext(Dispatchers.IO) { chainHoldingsOf(app, session, mode, account) }
            else -> emptyList()
        }
    }
    val ids = AssetRows.pricedIds(account, reads.tokens.orEmpty())
    LaunchedEffect(ids) {
        reads.prices = if (ids.isEmpty()) {
            emptyMap()
        } else {
            reads.prices + withContext(Dispatchers.IO) { runCatching { app.prices.assetPrices(ids) }.getOrDefault(emptyMap()) }
        }
    }
}

/**
 * The SPL tokens of a Solana account, or the assets of an Algorand account, that the address holds on [mode]'s network. Other chains have none, and a
 * read that fails shows none. It runs off the main thread.
 */
private suspend fun chainHoldingsOf(app: LumioApp, session: VaultSession, mode: NetworkMode, account: WalletAccount): List<ChainHolding> {
    val scheduler = app.nativeSchedulerFor(session)
    return runCatching {
        when (account.chain) {
            AccountChain.SOLANA -> {
                val network = if (mode == NetworkMode.TESTNET) SOLANA_DEVNET else SOLANA_MAINNET
                ChainHoldings.solana(scheduler, network, account.address, ChainHoldings.solanaNames(app), account.networkName)
            }
            AccountChain.ALGORAND -> {
                val network = if (mode.holds(ALGORAND_TESTNET.testnet)) ALGORAND_TESTNET else ALGORAND_MAINNET
                ChainHoldings.algorand(scheduler, network, account.address, account.networkName)
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())
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
 * The newest rows of [account] on [mode]'s side, from [reads] and then as they land. Its reads are planned as the screen opens, and they run then: the
 * Ethereum address on each enabled network, or the Bitcoin account's scan. A Bitcoin account's rows are those of every address that the scan turns up, and
 * each read that lands shows its rows. Solana and Algorand have no history provider yet, so they show none. Null until the first page is read.
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
        value = withContext(Dispatchers.IO) { accountPlanOf(app, session, mode, account) }
    }
    // The Bitcoin addresses that the scan has turned up, and a count of the reads that have landed. The reads write both as they run.
    val owned = remember(session, mode, account.walletId, account.chain, account.index) { MutableStateFlow(emptySet<String>()) }
    val landed = remember(session, mode, account.walletId, account.chain, account.index) { MutableStateFlow(0) }
    val ownedNow by owned.collectAsState()
    val landedNow by landed.collectAsState()
    LaunchedEffect(plan) {
        val current = plan ?: return@LaunchedEffect
        val open = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            TxSync.run(app, open, current, onStored = { landed.update { it + 1 } }, onAddress = { address -> owned.update { it + address } })
        }
    }
    val addresses = when (account.chain) {
        AccountChain.ETHEREUM -> setOf(account.address)
        AccountChain.BITCOIN -> ownedNow
        AccountChain.SOLANA, AccountChain.ALGORAND -> emptySet()
    }
    val items by produceState(reads.activity, plan, addresses, landedNow) {
        val current = plan ?: return@produceState
        val open = vault ?: return@produceState
        val page = withContext(Dispatchers.IO) { accountPage(app, open, addresses, current.networks, ACTIVITY_ROWS).items }
        reads.activity = page
        value = page
    }
    return items
}

/** The reads of [account] on [mode]'s side, planned from the open vault: the enabled networks of the side, and the Bitcoin account when it is one. */
private fun accountPlanOf(app: LumioApp, session: VaultSession, mode: NetworkMode, account: WalletAccount): TxSyncPlan {
    val settings = EvmSettingsStore.read(session)
    val chains = EvmCatalog.chains(app.registry, settings).filter { it.chainId in settings.enabled && mode.holds(it) }
    val bitcoin = if (account.chain == AccountChain.BITCOIN) bitcoinAccountRef(session, account.walletId, account.index, mode) else null
    return AccountActivity.plan(account, chains, bitcoin)
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
