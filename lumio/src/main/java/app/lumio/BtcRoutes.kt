package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.vault.VaultSession
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.BtcPortfolio
import app.lumio.btc.BtcRow
import app.lumio.btc.BtcStore
import app.lumio.btc.EsploraServers
import app.lumio.btc.scanAll
import app.lumio.pair.PairedWallets
import app.lumio.ui.btc.BitcoinAccountScreen
import app.lumio.ui.btc.BitcoinPortfolioScreen
import app.lumio.ui.settings.LumioSettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Every Bitcoin account of the open vault, with the wallet it came from. A vault that cannot be read has none. */
internal fun bitcoinRefs(session: VaultSession): List<BtcAccountRef> = try {
    PairedWallets.list(session).flatMap { wallet -> wallet.bitcoin.map { BtcAccountRef(wallet.id, it) } }
} catch (e: IllegalStateException) {
    emptyList()
}

/**
 * The Bitcoin portfolio. It opens on the cached scans at once, then scans every account over the network. A network whose server cannot
 * be reached keeps its cached rows, with the reason shown above them.
 */
@Composable
internal fun BitcoinRoute(navController: NavHostController, holder: VaultHolder) {
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val scope = rememberCoroutineScope()
    val store = remember(session) { BtcStore(session) }
    var rows by remember { mutableStateOf<List<BtcRow>>(emptyList()) }
    var failures by remember { mutableStateOf<Map<BtcNetwork, String>>(emptyMap()) }
    var scanning by remember { mutableStateOf(false) }

    fun refresh() {
        if (scanning) return
        scanning = true
        scope.launch {
            val refs = withContext(Dispatchers.IO) { bitcoinRefs(session) }
            val report = withContext(Dispatchers.IO) {
                store.retain(refs.map { it.key }.toSet())
                scanAll(refs, store.servers(), store) { System.currentTimeMillis() }
            }
            rows = withContext(Dispatchers.IO) { BtcPortfolio.rows(refs, store::snapshot, store.servers()) }
            failures = report.failures
            scanning = false
        }
    }

    LaunchedEffect(session) {
        rows = withContext(Dispatchers.IO) {
            BtcPortfolio.rows(bitcoinRefs(session), store::snapshot, store.servers())
        }
        refresh()
    }
    BitcoinPortfolioScreen(
        sections = BtcPortfolio.sections(rows),
        scanning = scanning,
        failures = failures,
        onOpen = { ref -> navController.openHidden(accountRoute(ref.key)) },
        onRefresh = ::refresh,
        onBack = { navController.popBackStack() },
    )
}

/** One Bitcoin account: its cached balance at once, then a fresh scan of that account. */
@Composable
internal fun BitcoinAccountRoute(navController: NavHostController, holder: VaultHolder, accountKey: String) {
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val scope = rememberCoroutineScope()
    val store = remember(session) { BtcStore(session) }
    var row by remember { mutableStateOf<BtcRow?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    fun refresh(ref: BtcAccountRef) {
        if (scanning) return
        scanning = true
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                scanAll(listOf(ref), store.servers(), store) { System.currentTimeMillis() }
            }
            row = withContext(Dispatchers.IO) { BtcPortfolio.rows(listOf(ref), store::snapshot, store.servers()).single() }
            failure = report.failures[ref.account.network]
            scanning = false
        }
    }

    LaunchedEffect(session, accountKey) {
        val ref = withContext(Dispatchers.IO) { bitcoinRefs(session).firstOrNull { it.key == accountKey } }
        if (ref == null) {
            navController.popBackStack()
            return@LaunchedEffect
        }
        row = withContext(Dispatchers.IO) { BtcPortfolio.rows(listOf(ref), store::snapshot, store.servers()).single() }
        refresh(ref)
    }
    val current = row ?: return
    BitcoinAccountScreen(
        row = current,
        scanning = scanning,
        failure = failure,
        onRefresh = { refresh(current.ref) },
        onBack = { navController.popBackStack() },
    )
}

/** Lumio's settings: the Esplora server of each Bitcoin network. A save that the servers refuse keeps the screen open with the reason. */
@Composable
internal fun LumioSettingsRoute(navController: NavHostController, holder: VaultHolder) {
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val scope = rememberCoroutineScope()
    val store = remember(session) { BtcStore(session) }
    var overrides by remember { mutableStateOf<Map<BtcNetwork, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session) {
        overrides = withContext(Dispatchers.IO) { store.servers() }
    }
    val current = overrides ?: return

    // A save keeps the owner's choice on screen when it stays, as Reset does, and leaves the screen when it is a Save.
    fun saveServers(next: Map<BtcNetwork, String>, leave: Boolean) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { store.saveServers(next) }.isSuccess }
            if (saved) {
                overrides = next
                error = null
                if (leave) navController.popBackStack()
            } else {
                error = "The servers could not be saved. Try again."
            }
        }
    }

    LumioSettingsScreen(
        mainnet = EsploraServers.baseUrl(BtcNetwork.MAINNET, current),
        testnet4 = EsploraServers.baseUrl(BtcNetwork.TESTNET4, current),
        error = error,
        onSave = { mainnet, testnet4 ->
            val parsed = runCatching { overridesFrom(mainnet, testnet4) }
            error = parsed.exceptionOrNull()?.message
            parsed.getOrNull()?.let { saveServers(it, leave = true) }
        },
        onReset = { saveServers(emptyMap(), leave = false) },
        onBack = { navController.popBackStack() },
    )
}

/** The route of the account [key]. Keys hold only letters, digits, dashes and underscores, so they need no escaping. */
internal fun accountRoute(key: String): String = "${LumioRoute.BITCOIN_ACCOUNT}/$key"

/**
 * The overrides that the owner's two texts name. A blank text, or the default address, is no override. A text that is not an https
 * address is refused, and the message names the network.
 * @throws IllegalArgumentException with the reason, naming the network that was refused.
 */
internal fun overridesFrom(mainnet: String, testnet4: String): Map<BtcNetwork, String> = buildMap {
    for ((network, text) in listOf(BtcNetwork.MAINNET to mainnet, BtcNetwork.TESTNET4 to testnet4)) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) continue
        val url = try {
            EsploraServers.normalize(trimmed)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("${if (network == BtcNetwork.MAINNET) "Mainnet" else "Testnet4"}: ${e.message}", e)
        }
        if (url != EsploraServers.defaultFor(network)) put(network, url)
    }
}
