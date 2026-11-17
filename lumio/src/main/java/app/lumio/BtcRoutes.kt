package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.vault.VaultSession
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.BtcPortfolio
import app.lumio.btc.BtcRow
import app.lumio.btc.EsploraServers
import app.lumio.btc.networkId
import app.lumio.data.config.ProviderConfig
import app.lumio.data.esplora.CustomEsploraProvider
import app.lumio.data.net.NetworkId
import app.lumio.data.scan.ScanJob
import app.lumio.data.scan.ScanQueue
import app.lumio.data.sched.Priority
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallets
import app.lumio.ui.settings.BitcoinServersSettings
import app.lumio.ui.settings.withServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The Bitcoin account [index] of the wallet [walletId], when it is on [mode]'s side. Null when the vault has no such account there. */
internal fun bitcoinAccountRef(session: VaultSession, walletId: String, index: Int, mode: NetworkMode): BtcAccountRef? =
    bitcoinRefs(session).filter { it.walletId == walletId }.getOrNull(index)?.takeIf { mode.holds(it.account.network) }

/** Every Bitcoin account of the open vault, with the wallet it came from. A vault that cannot be read has none. */
internal fun bitcoinRefs(session: VaultSession): List<BtcAccountRef> = try {
    PairedWallets.list(session).flatMap { wallet -> wallet.bitcoin.map { BtcAccountRef(wallet.id, it) } }
} catch (e: IllegalStateException) {
    emptyList()
}

/**
 * The Bitcoin part of the Networks page: the Esplora server of each Bitcoin network. Each valid server is saved as the owner sets it, and a save
 * that the vault refuses shows its reason under the fields. Saves run one after another under a lock, and each reads the configs it changes, so one
 * network's save never overwrites the other's.
 */
@Composable
internal fun BitcoinSettingsRoute(navController: NavHostController, holder: VaultHolder) {
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val app = LocalContext.current.applicationContext as LumioApp
    val scope = rememberCoroutineScope()
    val saves = remember { Mutex() }
    var overrides by remember { mutableStateOf<Map<BtcNetwork, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session) {
        overrides = withContext(Dispatchers.IO) { app.lumioVault()?.let { serverOverrides(it.configs()) }.orEmpty() }
    }
    val current = overrides ?: return

    // Saves the overrides that [edit] makes of the ones saved now, and shows the result as the servers in use.
    fun change(edit: (Map<BtcNetwork, String>) -> Map<BtcNetwork, String>) {
        scope.launch {
            val next = saves.withLock {
                withContext(Dispatchers.IO) {
                    val vault = app.lumioVault() ?: return@withContext null
                    runCatching {
                        val configs = vault.configs()
                        val next = edit(serverOverrides(configs))
                        vault.saveConfigs(withOverrides(configs, next))
                        next
                    }.getOrNull()
                }
            }
            if (next != null) {
                overrides = next
                error = null
            } else {
                error = "The servers could not be saved. Try again."
            }
        }
    }

    BitcoinServersSettings(
        mainnet = EsploraServers.baseUrl(BtcNetwork.MAINNET, current),
        testnet4 = EsploraServers.baseUrl(BtcNetwork.TESTNET4, current),
        error = error,
        onApply = { network, url -> change { it.withServer(network, url) } },
        onReset = { change { emptyMap() } },
    )
}

/** The owner's own servers that are set, by network. A network without one reads the public servers. */
internal fun serverOverrides(configs: Map<String, ProviderConfig>): Map<BtcNetwork, String> = buildMap {
    for (network in BtcNetwork.entries) {
        configs[CustomEsploraProvider.idFor(network.networkId().chain)]?.baseUrl?.let { put(network, it) }
    }
}

/** [configs] with the owner's servers replaced by [overrides]. Every other provider keeps its settings. */
internal fun withOverrides(configs: Map<String, ProviderConfig>, overrides: Map<BtcNetwork, String>): Map<String, ProviderConfig> =
    configs.filterKeys { !it.startsWith(CustomEsploraProvider.ID_PREFIX) } +
        overrides.entries.associate { (network, url) ->
            CustomEsploraProvider.idFor(network.networkId().chain) to ProviderConfig(baseUrl = url)
        }

/** The rows of [refs], each with its last known balance from the open vault. A locked or decoy vault has none, so its rows show no balance. */
internal suspend fun rowsOf(vault: LumioVault?, refs: List<BtcAccountRef>): List<BtcRow> {
    if (vault == null) return refs.map { BtcRow(it, null) }
    val cached = refs.mapNotNull { ref -> vault.store.snapshot(ref.key)?.let { ref.key to it } }.toMap()
    return BtcPortfolio.rows(refs) { key -> cached[key] }
}

/** The row of [ref], with its last known balance. */
internal suspend fun rowOf(vault: LumioVault?, ref: BtcAccountRef): BtcRow = rowsOf(vault, listOf(ref)).single()

/**
 * Queues one scan job for each account in [refs] that has none, and raises a queued job to [priority] when it is less urgent than that. A job is
 * never queued twice, so the queue holds one job per account.
 */
internal suspend fun queueScans(queue: ScanQueue, refs: List<BtcAccountRef>, net: NetworkId, priority: Priority) {
    val pending = queue.pending()
    for (ref in refs) {
        val queued = pending.firstOrNull { it.job.accountRef == ref.key }
        when {
            queued == null -> queue.enqueue(ScanJob(ref.walletId, net, ref.key, priority))
            queued.job.priority.rank > priority.rank -> queue.setPriority(queued.id, priority)
        }
    }
}
