package app.lumio

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
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.AccountAddresses
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.leaveHidden
import app.lensly.ui.navigation.openHidden
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.BtcSnapshot
import app.lumio.data.config.DisplaySettings
import app.lumio.data.config.DisplaySettingsStore
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.price.Price
import app.lumio.data.scan.ScanEvent
import app.lumio.home.AccountOption
import app.lumio.home.HomeAccounts
import app.lumio.home.HomeAssets
import app.lumio.home.HomeLoader
import app.lumio.home.NativeAsset
import app.lumio.home.PickTarget
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
    var balances by remember { mutableStateOf<List<NativeAsset>>(emptyList()) }
    var prices by remember { mutableStateOf<Map<NetworkId, Price>>(emptyMap()) }
    var wallets by remember { mutableStateOf<List<PairedWallet>>(emptyList()) }
    var bitcoinAccounts by remember { mutableStateOf<List<BtcAccountRef>>(emptyList()) }
    var entries by remember { mutableStateOf<Map<Long, NetworkEntry>>(emptyMap()) }
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
        wallets = withContext(Dispatchers.IO) { PairedWallets.list(session) }
        bitcoinAccounts = withContext(Dispatchers.IO) { bitcoinRefs(session).filter { mode.holds(it.account.network) } }
        balances = readOrNull { HomeLoader.cached(app, session, mode) } ?: balances
        refreshing = true
        notice = null
        try {
            readOrNull { HomeLoader.prices(app, balances) }?.let { prices = it }
            val ethereum = readOrNull { HomeLoader.refreshEvm(app, session, mode) }
            readOrNull { HomeLoader.cached(app, session, mode) }?.let { balances = it }
            readOrNull { HomeLoader.scanBitcoin(app, session, mode) }
            readOrNull { HomeLoader.cached(app, session, mode) }?.let { balances = it }
            readOrNull { HomeLoader.prices(app, balances) }?.let { prices = it }
            if (ethereum == null || ethereum.isNotEmpty()) notice = REFRESH_NOTICE
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
    )
}

/** The page of one coin: its balance and value on the side, and the accounts that hold it. Missing when the coin is not on the side. */
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
            val wallets = withContext(Dispatchers.IO) { PairedWallets.list(open) }
            val hidden = withContext(Dispatchers.IO) { !DisplaySettingsStore.read(open).showAssets }
            val entry = withContext(Dispatchers.IO) { networkEntryOf(app, asset.network) }
            AssetLoad.Ready(priced, entry, HomeAccounts.holding(priced, wallets), hidden)
        }
    }
    when (val current = load) {
        null -> return
        AssetLoad.Missing -> {
            LaunchedEffect(Unit) { navController.popBackStack() }
            return
        }
        is AssetLoad.Ready -> AssetScreen(
            asset = current.asset,
            entry = current.entry,
            accounts = current.accounts,
            hidden = current.hidden,
            onAccount = { option -> accountRoute(option.target)?.let { navController.navigate(it) } },
            onBack = { navController.popBackStack() },
            onLock = { leaveHidden(navController, holder) },
        )
    }
}

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
            onLock = { leaveHidden(navController, holder) },
        )
    }
}

/** The Wallets section of the settings: the paired wallets, with pairing and deletion. A deletion is saved, then Home reads the wallets again. */
@Composable
internal fun WalletsSettingsRoute(navController: NavHostController, holder: VaultHolder, listVersion: Int, onChanged: () -> Unit) {
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    val scope = rememberCoroutineScope()
    val wallets by produceState<List<PairedWallet>>(initialValue = emptyList(), session, listVersion) {
        value = if (session == null) emptyList() else onVault(emptyList()) { PairedWallets.list(session) }
    }
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

/** The state of [AssetRoute]: the coin, or missing when it is not on the side. */
private sealed interface AssetLoad {
    data object Missing : AssetLoad

    class Ready(
        val asset: NativeAsset,
        val entry: NetworkEntry?,
        val accounts: List<AccountOption>,
        val hidden: Boolean,
    ) : AssetLoad
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
        val account = wallet?.evm
        if (wallet == null || account == null) {
            null
        } else {
            ReceiveLoad.Ready(
                walletName = wallet.name,
                detail = "Ethereum and every EVM network, address 1",
                address = AccountAddresses.addressAt(account, 0),
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

/** The route of a Send from [target], or null when the target does not send. */
internal fun sendRoute(target: PickTarget): String? = when (target) {
    is PickTarget.Evm -> LumioRoute.send(target.walletId, 0)
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
