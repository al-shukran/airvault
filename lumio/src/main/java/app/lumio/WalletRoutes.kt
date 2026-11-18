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
import app.lumio.chains.ChainHolding
import app.lumio.chains.ChainHoldings
import app.lumio.data.WallClock
import app.lumio.data.asset.AssetId
import app.lumio.data.config.DisplaySettings
import app.lumio.data.price.ChartRange
import app.lumio.data.price.Price
import app.lumio.data.price.PriceSeries
import app.lumio.data.provider.algorand.ALGORAND_MAINNET
import app.lumio.data.provider.algorand.ALGORAND_TESTNET
import app.lumio.data.provider.solana.SOLANA_DEVNET
import app.lumio.data.provider.solana.SOLANA_MAINNET
import app.lumio.data.scan.ScanEvent
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmSettingsStore
import app.lumio.network.NetworkMode
import app.lumio.network.NetworkModeStore
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.tokens.TokenBalanceCache
import app.lumio.tokens.TokenHolding
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
import app.lumio.ui.wallets.WalletRow
import app.lumio.ui.wallets.WalletScreen
import app.lumio.ui.wallets.WalletsScreen
import app.lumio.ui.wallets.summaryLine
import app.lumio.wallets.AccountActivity
import app.lumio.wallets.AccountChain
import app.lumio.wallets.WalletAccount
import app.lumio.wallets.WalletAccounts
import app.lumio.wallets.WalletsLoader
import app.lumio.wallets.WalletsView
import app.lumio.wallets.readOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The notice a read that did not answer leaves on the wallet screens. The last known balances stay on them. */
private const val REFRESH_NOTICE = "Some balances could not be refreshed. The last known balances are shown."

/** How many of an account's newest transactions its screen shows. */
private const val ACTIVITY_ROWS = 10

/**
 * What the wallet screens show: the balances of the side, whether a read runs and its notice, the time of the last complete read, the display settings,
 * and a refresh.
 */
private class WalletsState(
    val view: WalletsView?,
    val refreshing: Boolean,
    val notice: String?,
    val updatedAtMs: Long?,
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
    var updatedAtMs by remember { mutableStateOf<Long?>(null) }
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
            if (failed) notice = REFRESH_NOTICE else updatedAtMs = WallClock.nowMs()
        } finally {
            refreshing = false
        }
    }
    return WalletsState(view, refreshing, notice, updatedAtMs, display) { if (!refreshing) refreshTick++ }
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
    LaunchedEffect(session) {
        selection.mode = withContext(Dispatchers.IO) { NetworkModeStore.read(session) }
    }
    val state = rememberWalletsState(app, session, selection.mode, listVersion, refreshOnOpen = true)
    val view = state.view ?: return
    WalletsScreen(
        rows = view.wallets.map { wallet -> walletRow(view, wallet) },
        totalUsd = allWalletsUsd(view),
        showTotal = state.display.showTotal,
        refreshing = state.refreshing,
        updatedAtMs = state.updatedAtMs,
        notice = state.notice,
        onWallet = { id -> navController.navigate(LumioRoute.wallet(id)) },
        onPair = { navController.navigate(LumioRoute.PAIR_SCAN) },
        onRefresh = state.refresh,
        onSettings = { navController.navigate(LumioRoute.SETTINGS) { launchSingleTop = true } },
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
    val accounts = view.accountsOf(walletId)
    WalletScreen(
        walletId = walletId,
        walletName = wallet.name,
        fingerprint = wallet.fingerprint.toInt(),
        mode = mode,
        accounts = accounts,
        tokenCounts = rememberTokenCounts(app, session, mode, accounts),
        network = app.registry::network,
        totalUsd = view.usd(walletId),
        showTotal = state.display.showTotal,
        showBalances = state.display.showAssets,
        refreshing = state.refreshing,
        updatedAtMs = state.updatedAtMs,
        notice = state.notice,
        onAccount = { account -> navController.navigate(LumioRoute.account(walletId, account.chain, account.index)) },
        onRefresh = state.refresh,
        onBack = { navController.popBackStack() },
    )
}

/**
 * One account of a wallet on the side [mode] chooses: its chain, address and balance, its ERC-20 balances, its recent activity, and the Send and Receive
 * actions. The balances and the tokens come from the caches, so opening it reads no balance from the network. Its activity is read from the providers as
 * it opens, and its rows show as each read lands. Missing when the account is not in the open vault.
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
    // Opening the account reads the networks, as opening the wallet does, so the balance shown is the one the network reports now.
    val state = rememberWalletsState(app, session, mode, listVersion, refreshOnOpen = true)
    val view = state.view ?: return
    val account = view.accountsOf(walletId).firstOrNull { it.chain == chain && it.index == index }
    if (account == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val tokens = rememberAccountTokens(app, session, mode, account)
    val chainHoldings = rememberChainHoldings(app, session, mode, account)
    val activity = rememberAccountActivity(app, session, mode, account)
    AccountScreen(
        walletName = view.wallets.firstOrNull { it.id == walletId }?.name.orEmpty(),
        account = account,
        showBalance = state.display.showAssets,
        assets = rememberAssetRows(app, account, tokens, chainHoldings),
        activity = activity,
        refreshing = state.refreshing,
        notice = state.notice,
        onRefresh = state.refresh,
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
 * account's rows by [assetKey], and a missing one (its holding has gone) leaves the screen. Send and Receive act on the account.
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
    val state = rememberWalletsState(app, session, mode, listVersion, refreshOnOpen = true)
    val view = state.view ?: return
    val account = view.accountsOf(walletId).firstOrNull { it.chain == chain && it.index == index }
    if (account == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val tokens = rememberAccountTokens(app, session, mode, account)
    val chainHoldings = rememberChainHoldings(app, session, mode, account)
    val asset = rememberAssetRows(app, account, tokens, chainHoldings).firstOrNull { it.assetKey == assetKey }
    if (asset == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val chart by produceState<PriceSeries?>(null, asset.assetId) {
        value = asset.assetId?.let { id ->
            withContext(Dispatchers.IO) { runCatching { app.prices.chart(id, ChartRange.D7) }.getOrNull() }
        }
    }
    val activity = rememberAccountActivity(app, session, mode, account)
    AssetScreen(
        asset = asset,
        showBalance = state.display.showAssets,
        chart = chart,
        activity = activity.filter { AssetRows.assetKeyOf(it.tx) == assetKey },
        onSend = { navController.navigate(sendRoute(account, mode)) },
        onReceive = { navController.navigate(LumioRoute.receive(walletId, chain, index)) },
        onOpenTransaction = { item -> navController.navigate(LumioRoute.transaction(walletId, item.tx.account, item.tx.id)) },
        onBack = { navController.popBackStack() },
    )
}

/**
 * The assets of [account] as its rows: the native coin, the ERC-20 tokens, and the SPL tokens or Algorand assets. A priced row shows its value in
 * dollars once its price is read, and until then it shows its amount alone.
 */
@Composable
private fun rememberAssetRows(app: LumioApp, account: WalletAccount, tokens: List<TokenHolding>, holdings: List<ChainHolding>): List<AssetRowModel> {
    val prices = rememberAssetPrices(app, account, tokens)
    return remember(account, tokens, holdings, prices) {
        AssetRows.of(account, tokens, holdings, prices, app.registry::network, app.registry::token)
    }
}

/** The USD prices of the coins that [account] and its [tokens] can have, read as the screen opens. A failed read leaves the rows unpriced. */
@Composable
private fun rememberAssetPrices(app: LumioApp, account: WalletAccount, tokens: List<TokenHolding>): Map<AssetId, Price> {
    val ids = AssetRows.pricedIds(account, tokens)
    val prices by produceState<Map<AssetId, Price>>(emptyMap(), ids) {
        value = if (ids.isEmpty()) {
            emptyMap()
        } else {
            withContext(Dispatchers.IO) { runCatching { app.prices.assetPrices(ids) }.getOrDefault(emptyMap()) }
        }
    }
    return prices
}

/** The ERC-20 balances the cache holds for the Ethereum address of [account], on the enabled networks of [mode]'s side. Empty for other chains. */
@Composable
private fun rememberAccountTokens(app: LumioApp, session: VaultSession, mode: NetworkMode, account: WalletAccount): List<TokenHolding> {
    val tokens by produceState<List<TokenHolding>>(emptyList(), session, mode, account.walletId, account.chain, account.index) {
        value = if (account.chain != AccountChain.ETHEREUM) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                val settings = EvmSettingsStore.read(session)
                val chains = EvmCatalog.chains(app.registry, settings).filter { it.chainId in settings.enabled && mode.holds(it) }
                TokenPortfolio.holdingsAt(account.walletId, account.address, chains, app.tokensFor(session), TokenBalanceCache.read(session))
                    .filter { it.amount.signum() > 0 }
            }
        }
    }
    return tokens
}

/**
 * The SPL tokens of a Solana account, or the assets of an Algorand account, that the address holds on [mode]'s network, read as the screen opens. Other
 * chains have none. A read that fails shows no holdings, and the rest of the screen stays as it is.
 */
@Composable
private fun rememberChainHoldings(app: LumioApp, session: VaultSession, mode: NetworkMode, account: WalletAccount): List<ChainHolding> {
    val holdings by produceState<List<ChainHolding>>(emptyList(), session, mode, account.walletId, account.chain, account.index) {
        value = withContext(Dispatchers.IO) {
            val scheduler = app.nativeSchedulerFor(session)
            runCatching {
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
    }
    return holdings
}

/**
 * The newest rows of [account] on [mode]'s side. Its reads are planned as the screen opens, and they run then: the Ethereum address on each enabled
 * network, or the Bitcoin account's scan. A Bitcoin account's rows are those of every address that the scan turns up, and each read that lands shows its
 * rows. Solana and Algorand have no history provider yet, so they show none.
 */
@Composable
private fun rememberAccountActivity(app: LumioApp, session: VaultSession, mode: NetworkMode, account: WalletAccount): List<TxFeedItem> {
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
    val items by produceState<List<TxFeedItem>>(emptyList(), plan, addresses, landedNow) {
        val current = plan ?: return@produceState
        val open = vault ?: return@produceState
        value = withContext(Dispatchers.IO) { accountPage(app, open, addresses, current.networks, ACTIVITY_ROWS).items }
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
 * The tokens that each Ethereum account of [accounts] holds on [mode]'s enabled networks, read from the vault's caches as the screen opens. Other chains
 * have no tokens here, so they are not counted.
 */
@Composable
private fun rememberTokenCounts(app: LumioApp, session: VaultSession, mode: NetworkMode, accounts: List<WalletAccount>): Map<WalletAccount, Int> {
    val counts by produceState<Map<WalletAccount, Int>>(emptyMap(), session, mode, accounts) {
        value = withContext(Dispatchers.IO) {
            val settings = EvmSettingsStore.read(session)
            val chains = EvmCatalog.chains(app.registry, settings).filter { it.chainId in settings.enabled && mode.holds(it) }
            val cache = TokenBalanceCache.read(session)
            accounts.filter { it.chain == AccountChain.ETHEREUM }.associateWith { account ->
                TokenPortfolio.holdingsAt(account.walletId, account.address, chains, app.tokensFor(session), cache).count { it.amount.signum() > 0 }
            }
        }
    }
    return counts
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
