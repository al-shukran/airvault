package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.AccountAddresses
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.BtcSnapshot
import app.lumio.data.asset.AssetId
import app.lumio.data.config.DisplaySettings
import app.lumio.data.config.DisplaySettingsStore
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.price.ChartRange
import app.lumio.data.price.Price
import app.lumio.data.price.PriceSeries
import app.lumio.data.scan.ScanEvent
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAssetRef
import app.lumio.data.tx.TxQuery
import app.lumio.home.AccountOption
import app.lumio.home.HomeAccounts
import app.lumio.home.HomeAsset
import app.lumio.home.HomeAssets
import app.lumio.home.HomeLoader
import app.lumio.home.TokenAsset
import app.lumio.home.PickTarget
import app.lumio.network.NetworkMode
import app.lumio.network.NetworkModeStore
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.ui.asset.AssetScreen
import app.lumio.ui.home.LumioHome
import app.lumio.ui.receive.ReceiveScreen
import app.lumio.ui.settings.WalletsSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many transactions the asset page lists, the newest first. */
private const val ASSET_TX_PAGE = 50

/** The notice Home shows when a read did not answer. The last known balances stay on the list. */
private const val REFRESH_NOTICE = "Some balances could not be refreshed. The last known balances are shown."

/**
 * Home's data and actions. It shows the cached balances at once, then reads the prices, the Ethereum balances and the Bitcoin scans. Each read adds to
 * the list when it lands, and pull to refresh reads them all again. Sorting, the side and the pickers write through the open vault.
 */
@Composable
internal fun HomeRoute(
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
    val vault = app.lumioVault()
    val fallback = remember { MutableStateFlow(DisplaySettings()) }
    val display by (vault?.display ?: fallback).collectAsState()
    val scope = rememberCoroutineScope()
    val mode = selection.mode
    var balances by remember { mutableStateOf<List<HomeAsset>>(emptyList()) }
    var prices by remember { mutableStateOf<Map<AssetId, Price>>(emptyMap()) }
    var wallets by remember { mutableStateOf<List<PairedWallet>>(emptyList()) }
    var bitcoinAccounts by remember { mutableStateOf<List<BtcAccountRef>>(emptyList()) }
    var entries by remember { mutableStateOf<Map<Long, NetworkEntry>>(emptyMap()) }
    // The registry's tokens by asset ID, so a token row shows its own logo. The ID is the one TokenAsset gives the token.
    var tokenEntries by remember { mutableStateOf<Map<String, TokenEntry>>(emptyMap()) }
    var refreshing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // The list follows each scan as its account finishes, so a balance shows as soon as it is read.
    LaunchedEffect(session, mode) {
        app.scanQueue.events.collect { event ->
            when (event) {
                is ScanEvent.Finished -> readOrNull { HomeLoader.cached(app, session, mode) }?.let { balances = it }
                is ScanEvent.Failed -> notice = REFRESH_NOTICE
                is ScanEvent.Progress -> Unit
            }
        }
    }

    // Every load runs in this one effect, so a new load cancels the one before it: a read of the old side can never write over the new one. A side,
    // a pairing, a deletion and a pull to refresh each start a load. The cached balances show at once; then the prices, the Ethereum balances and the
    // Bitcoin scans, each adding to the list as it lands.
    var refreshTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(session, mode, listVersion, refreshTick) {
        entries = withContext(Dispatchers.IO) { app.registry.networks().associateBy { it.chainId } }
        tokenEntries = withContext(Dispatchers.IO) {
            app.registry.tokens().associateBy { TokenAsset.idOf(NetworkId(Family.EVM, it.chainId.toString(), testnet = false), it.contract) }
        }
        wallets = withContext(Dispatchers.IO) { PairedWallets.list(session) }
        bitcoinAccounts = withContext(Dispatchers.IO) { bitcoinRefs(session).filter { mode.holds(it.account.network) } }
        balances = readOrNull { HomeLoader.cached(app, session, mode) } ?: balances
        refreshing = true
        notice = null
        try {
            readOrNull { HomeLoader.prices(app, balances) }?.let { prices = it }
            val ethereum = readOrNull { HomeLoader.refreshEvm(app, session, mode) }
            val nativeFailures = readOrNull { HomeLoader.refreshNative(app, session, mode) }
            readOrNull { HomeLoader.cached(app, session, mode) }?.let { balances = it }
            readOrNull { HomeLoader.scanBitcoin(app, session, mode) }
            readOrNull { HomeLoader.cached(app, session, mode) }?.let { balances = it }
            readOrNull { HomeLoader.prices(app, balances) }?.let { prices = it }
            if (ethereum == null || ethereum.isNotEmpty() || nativeFailures == null || nativeFailures.isNotEmpty()) notice = REFRESH_NOTICE
        } finally {
            refreshing = false
        }
    }

    val assets = HomeAssets.priced(balances, prices)
    val sendOptions = remember(mode, wallets, bitcoinAccounts) { HomeAccounts.sendFrom(mode, wallets, bitcoinAccounts) }
    val receiveOptions = remember(wallets, bitcoinAccounts) { HomeAccounts.receiveFor(wallets, bitcoinAccounts) }
    LumioHome(
        assets = assets,
        settings = display,
        mode = mode,
        onMode = { next ->
            selection.mode = next
            scope.launch { onVault(Unit) { NetworkModeStore.write(session, next) } }
        },
        onUpdateDisplay = { change ->
            scope.launch { onVault(Unit) { vault?.updateDisplay(change) ?: Unit } }
        },
        onLock = onLock,
        onAsset = { asset -> navController.navigate(LumioRoute.asset(asset.id)) },
        onPair = { navController.navigate(LumioRoute.PAIR_SCAN) },
        hasWallets = wallets.isNotEmpty(),
        refreshing = refreshing,
        onRefresh = { if (!refreshing) refreshTick++ },
        staleSinceMs = HomeAssets.staleSinceMs(assets, prices),
        notice = notice,
        sendOptions = sendOptions,
        receiveOptions = receiveOptions,
        onSend = { option -> sendRoute(option.target)?.let { navController.navigate(it) } },
        onReceive = { option -> receiveRoute(option.target)?.let { navController.navigate(it) } },
        networkEntry = { asset ->
            if (asset.network.family == Family.EVM) asset.network.chain.toLongOrNull()?.let { entries[it] } else null
        },
        tokenEntry = { asset -> tokenEntries[asset.id] },
    )
}

/**
 * The page of one coin: its balance and value, its price chart, its transactions, and the actions that send and receive it. Missing when the coin is
 * not on the side. The coin and its accounts are read from the vault, and the chart and the prices from the price repository.
 */
@Composable
internal fun AssetRoute(navController: NavHostController, holder: VaultHolder, assetId: String, selection: NetworkSelection) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val mode = selection.mode
    val load by produceState<AssetLoad?>(initialValue = null, session, mode, assetId) {
        val open = session
        val balances = HomeLoader.cached(app, open, mode)
        val asset = balances.firstOrNull { it.id == assetId }
        value = if (asset == null) {
            AssetLoad.Missing
        } else {
            val priced = HomeAssets.priced(listOf(asset), HomeLoader.prices(app, balances)).single()
            withContext(Dispatchers.IO) { assetPageOf(app, open, mode, priced) }
        }
    }
    when (val current = load) {
        null -> return
        AssetLoad.Missing -> {
            LaunchedEffect(Unit) { navController.popBackStack() }
            return
        }
        is AssetLoad.Ready -> AssetPage(navController = navController, holder = holder, app = app, current = current)
    }
}

/**
 * The page of [current]'s coin once it is read. Its chart is read for the range the owner picks, and each range is read again only when it is not kept
 * in memory. The actions open the send and receive of the coin, with the chain of an EVM coin already chosen.
 */
@Composable
private fun AssetPage(navController: NavHostController, holder: VaultHolder, app: LumioApp, current: AssetLoad.Ready) {
    var range by rememberSaveable { mutableStateOf(ChartRange.H24) }
    var chart by remember { mutableStateOf<ChartLoad>(ChartLoad.Loading) }
    LaunchedEffect(current.asset.assetId, range) {
        chart = ChartLoad.Loading
        val series = withContext(Dispatchers.IO) { app.prices.chart(current.asset.assetId, range) }
        chart = ChartLoad.Done(series)
    }
    val loaded = chart
    AssetScreen(
        asset = current.asset,
        entry = current.entry,
        hidden = current.hidden,
        range = range,
        onRange = { range = it },
        series = (loaded as? ChartLoad.Done)?.series,
        chartLoading = loaded is ChartLoad.Loading,
        transactions = current.transactions,
        sendOptions = current.sendOptions,
        receiveOptions = current.receiveOptions,
        accounts = current.accounts,
        onSend = { option ->
            val token = (current.asset as? TokenAsset)?.contract
            sendRoute(option.target, chainIdOf(current.asset), token)?.let { navController.navigate(it) }
        },
        onReceive = { option -> receiveRoute(option.target)?.let { navController.navigate(it) } },
        onAccount = { option -> accountRoute(option.target)?.let { navController.navigate(it) } },
        onBack = { navController.popBackStack() },
    )
}

/**
 * What the page of [asset] reads from the open vault: its accounts, its transactions, its settings and its registry entry, and the Send and Receive
 * options for it. It reads no network. The transactions are the coin's on its network, in every account, and spam only when the owner shows it.
 */
private suspend fun assetPageOf(app: LumioApp, session: VaultSession, mode: NetworkMode, asset: HomeAsset): AssetLoad.Ready {
    val wallets = PairedWallets.list(session)
    val bitcoin = bitcoinRefs(session)
    val settings = DisplaySettingsStore.read(session)
    val transactions = app.lumioVault()?.txs?.page(assetTxQuery(asset, settings), 0, ASSET_TX_PAGE).orEmpty()
    return AssetLoad.Ready(
        asset = asset,
        entry = networkEntryOf(app, asset.network),
        accounts = HomeAccounts.holding(asset, wallets),
        hidden = !settings.showAssets,
        transactions = transactions,
        sendOptions = HomeAccounts.sendFor(asset, mode, wallets, bitcoin),
        receiveOptions = HomeAccounts.receiveFor(asset, wallets, bitcoin),
    )
}

/** The query of the transactions of [asset]: its coin on its network, in every account. */
private fun assetTxQuery(asset: HomeAsset, settings: DisplaySettings): TxQuery = TxQuery(
    assets = setOf(TxAssetRef(asset.network, (asset as? TokenAsset)?.contract)),
    includeSpam = !settings.hideSpam,
)

/** The chain ID of an EVM coin, which starts its send, or null for a Bitcoin coin. */
private fun chainIdOf(asset: HomeAsset): Long? = if (asset.network.family == Family.EVM) asset.network.chain.toLongOrNull() else null

/** The page that receives to one account, read from the vault. Missing when the account is not in the open vault. */
@Composable
internal fun ReceiveRoute(navController: NavHostController, holder: VaultHolder, kind: String, key: String) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val load by produceState<ReceiveLoad?>(initialValue = null, session, kind, key) {
        val open = session
        value = withContext(Dispatchers.IO) { receiveOf(app, open, kind, key) } ?: ReceiveLoad.Missing
    }
    when (val current = load) {
        null -> return
        ReceiveLoad.Missing -> {
            LaunchedEffect(Unit) { navController.popBackStack() }
            return
        }
        is ReceiveLoad.Ready -> ReceiveScreen(
            walletName = current.walletName,
            detail = current.detail,
            address = current.address,
            bitcoin = current.bitcoin,
            onBack = { navController.popBackStack() },
        )
    }
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

/** The Wallets page: the pairing of a wallet from Lensly, and the paired wallets with deletion. A deletion is saved, then Home reads the wallets again. */
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

/** The state of [AssetRoute]: the coin with what its page shows, or missing when it is not on the side. */
private sealed interface AssetLoad {
    data object Missing : AssetLoad

    class Ready(
        val asset: HomeAsset,
        val entry: NetworkEntry?,
        val accounts: List<AccountOption>,
        val hidden: Boolean,
        val transactions: List<LumioTx>,
        val sendOptions: List<AccountOption>,
        val receiveOptions: List<AccountOption>,
    ) : AssetLoad
}

/** The state of the price chart on the page: being read, or read with the series a source gave, which is null when none answered. */
private sealed interface ChartLoad {
    data object Loading : ChartLoad

    class Done(val series: PriceSeries?) : ChartLoad
}

/** The state of [ReceiveRoute]: the address and what it belongs to, or missing when the account is not in the open vault. */
private sealed interface ReceiveLoad {
    data object Missing : ReceiveLoad

    class Ready(val walletName: String, val detail: String, val address: String, val bitcoin: Boolean) : ReceiveLoad
}

/** The receive page of the account [key] of [kind], from the open vault. Null when the account is not there. */
private suspend fun receiveOf(app: LumioApp, session: VaultSession, kind: String, key: String): ReceiveLoad? = when (kind) {
    LumioRoute.KIND_EVM -> {
        val wallet = PairedWallets.list(session).firstOrNull { it.id == key }
        val account = wallet?.evmKeys?.firstOrNull()
        if (wallet == null || account == null) {
            null
        } else {
            ReceiveLoad.Ready(
                walletName = wallet.name,
                detail = "Ethereum and every EVM network, address 1",
                address = AccountAddresses.addressOfKey(account),
                bitcoin = false,
            )
        }
    }
    LumioRoute.KIND_BTC -> {
        val ref = bitcoinRefs(session).firstOrNull { it.key == key }
        if (ref == null) {
            null
        } else {
            val snapshot: BtcSnapshot? = rowOf(app.lumioVault(), ref).snapshot
            val wallet = PairedWallets.list(session).firstOrNull { it.id == ref.walletId }?.name.orEmpty()
            val side = if (ref.account.network == BtcNetwork.TESTNET4) "testnet4" else "mainnet"
            ReceiveLoad.Ready(
                walletName = wallet,
                detail = "Bitcoin ${HomeAccounts.scriptLabel(ref.account.scriptType)} on $side",
                address = snapshot?.nextReceiveAddress ?: ref.account.receiveAddress(0),
                bitcoin = true,
            )
        }
    }
    else -> null
}

/** The registry entry that names the EVM network of [network], which gives the chain's logo. Null for a Bitcoin network or a custom chain. */
private fun networkEntryOf(app: LumioApp, network: NetworkId): NetworkEntry? =
    if (network.family == Family.EVM) app.registry.networks().firstOrNull { it.chainId.toString() == network.chain } else null

/**
 * The route of a Send from [target], or null when the target does not send. [chainId] starts the form on that chain, and [token] on that ERC-20
 * contract, so an asset's page starts on the coin it shows.
 */
internal fun sendRoute(target: PickTarget, chainId: Long? = null, token: String? = null): String? = when (target) {
    is PickTarget.Evm -> LumioRoute.send(target.walletId, 0, chainId, token)
    PickTarget.Bitcoin -> LumioRoute.BTC_SEND
    is PickTarget.BitcoinAccount, is PickTarget.BitcoinWallet -> null
}

/** The route of a Receive to [target], or null when the target has no address to show. */
internal fun receiveRoute(target: PickTarget): String? = when (target) {
    is PickTarget.Evm -> LumioRoute.receive(LumioRoute.KIND_EVM, target.walletId)
    is PickTarget.BitcoinAccount -> LumioRoute.receive(LumioRoute.KIND_BTC, target.key)
    is PickTarget.BitcoinWallet, PickTarget.Bitcoin -> null
}

/** The page that holds the accounts of [target]: a wallet's Ethereum or Bitcoin page, or the page of one Bitcoin account. */
internal fun accountRoute(target: PickTarget): String? = when (target) {
    is PickTarget.Evm -> LumioRoute.wallet(target.walletId)
    is PickTarget.BitcoinWallet -> LumioRoute.bitcoin(target.walletId)
    is PickTarget.BitcoinAccount -> accountRoute(target.key)
    PickTarget.Bitcoin -> null
}

/**
 * [block], or null when it fails. A cancellation is passed on, so a screen that leaves stops its reads, and any other failure is a read that did not
 * answer, which leaves the list as it was.
 */
private suspend fun <T> readOrNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
