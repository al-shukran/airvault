package app.lumio

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
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
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.core.vault.VaultSession
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.PickerStop
import app.lensly.ui.navigation.openHidden
import app.lumio.data.config.DisplaySettings
import app.lumio.data.tx.CommentImportException
import app.lumio.data.tx.TxCommentTransfer
import app.lumio.data.tx.TxFilter
import app.lumio.data.tx.TxKeys
import app.lumio.data.tx.TxViewStore
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmSettingsStore
import app.lumio.home.HomeLoader
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallets
import app.lumio.transactions.TxAssetOption
import app.lumio.transactions.TxDetail
import app.lumio.transactions.TxPage
import app.lumio.transactions.TxSync
import app.lumio.transactions.TxSyncPlan
import app.lumio.transactions.assetOptions
import app.lumio.ui.settings.CommentBackupRows
import app.lumio.transactions.pricedPage
import app.lumio.transactions.txDetailOf
import app.lumio.transactions.txPage
import app.lumio.ui.transactions.TransactionDetailScreen
import app.lumio.ui.transactions.TransactionsScreen
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many rows one page of the feed holds. The next page is one tap away. */
private const val PAGE_SIZE = 50

/**
 * The Transactions tab. It shows the feed from the store at once, then reads the side's histories through the scheduler and adds each read as it lands.
 * A tab open, a pull to refresh and a change of side each read the providers again. The filter and the sort are the owner's, and the sort is remembered.
 */
@Composable
internal fun TransactionsRoute(navController: NavHostController, holder: VaultHolder, mode: NetworkMode) {
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

    val plan by produceState<TxSyncPlan?>(null, session, mode) {
        value = withContext(Dispatchers.IO) { planOf(app, session, mode) }
    }
    val assets by produceState<List<TxAssetOption>>(emptyList(), session, mode) {
        value = withContext(Dispatchers.IO) { assetOptions(HomeLoader.cached(app, session, mode)) }
    }
    val saved by produceState<TxViewStore.Sort?>(null, session) {
        value = withContext(Dispatchers.IO) { TxViewStore.read(session) }
    }
    var filter by remember { mutableStateOf(TxFilter()) }
    LaunchedEffect(saved) {
        saved?.let { filter = filter.copy(sort = it.sort, descending = it.descending) }
    }

    var limit by remember { mutableIntStateOf(PAGE_SIZE) }
    var page by remember { mutableStateOf(TxPage.EMPTY) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val stored = remember { MutableStateFlow(0) }
    val storedCount by stored.collectAsState()

    // A tab open, a pull to refresh and a change of side each read the providers. Each read that lands bumps [stored], and the feed reads the store again.
    LaunchedEffect(plan, refreshTick) {
        val current = plan ?: return@LaunchedEffect
        val open = vault ?: return@LaunchedEffect
        refreshing = true
        try {
            withContext(Dispatchers.IO) { TxSync.run(app, open, current, onStored = { stored.update { it + 1 } }) }
        } finally {
            refreshing = false
        }
    }

    // The feed shows the store's rows first, then their values in dollars once the prices are read.
    LaunchedEffect(plan, filter, limit, storedCount) {
        val current = plan ?: return@LaunchedEffect
        val open = vault ?: return@LaunchedEffect
        val rows = withContext(Dispatchers.IO) { txPage(app, open, current, filter, limit, hideSpam = true) }
        page = rows
        page = withContext(Dispatchers.IO) { pricedPage(app, rows) }
    }

    fun change(next: TxFilter) {
        val old = filter
        filter = next
        limit = PAGE_SIZE
        if (next.sort != old.sort || next.descending != old.descending) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    TxViewStore.write(session, TxViewStore.Sort(next.sort, next.descending))
                }
            }
        }
    }

    TransactionsScreen(
        rows = page.items,
        count = page.count,
        filter = filter,
        assets = assets,
        refreshing = refreshing,
        onFilter = ::change,
        onMore = { limit += PAGE_SIZE },
        onOpen = { row -> navController.navigate(LumioRoute.transaction(row.tx.account, row.tx.id)) },
        onRefresh = { refreshTick++ },
    )
}

/** The detail of one transaction, with its comment that the owner edits here. A transaction the store does not hold leaves the screen. */
@Composable
internal fun TransactionDetailRoute(navController: NavHostController, holder: VaultHolder, account: String, txId: String) {
    val app = LocalContext.current.applicationContext as LumioApp
    val context = LocalContext.current
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val vault = app.lumioVault()
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val load by produceState<DetailLoad?>(null, account, txId, version) {
        val open = vault
        value = if (open == null) {
            DetailLoad.Missing
        } else {
            withContext(Dispatchers.IO) { txDetailOf(app, open, account, txId) }?.let { DetailLoad.Ready(it) } ?: DetailLoad.Missing
        }
    }
    when (val current = load) {
        null -> return
        DetailLoad.Missing -> {
            LaunchedEffect(Unit) { navController.popBackStack() }
            return
        }
        is DetailLoad.Ready -> TransactionDetailScreen(
            detail = current.detail,
            onBack = { navController.popBackStack() },
            onExplorer = {
                current.detail.explorerUrl?.let { openExplorer(context, it) }
            },
            onSaveComment = { text ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        // A blank text clears the comment, as the store does.
                        vault?.txs?.setComment(txId, text, System.currentTimeMillis())
                    }
                    version++
                }
            },
        )
    }
}

/** The state of [TransactionDetailRoute]: the transaction, or missing when the store does not hold it. */
private sealed interface DetailLoad {
    data object Missing : DetailLoad

    class Ready(val detail: TxDetail) : DetailLoad
}

/**
 * The comment backup in Settings: export the comments to a file the owner picks, and import them from one. The file is sealed under the vault's export key,
 * so only this vault can open it. Each action reports its result under the rows.
 */
@Composable
internal fun CommentBackupRoute(holder: VaultHolder) {
    val context = LocalContext.current
    val app = context.applicationContext as LumioApp
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    // Each picker is a launch of the app's own. It stops the app while it is open, and that stop and the time away never lock the vault.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EXPORT_TYPE)) { uri ->
        PickerStop.disarm()
        if (uri != null) {
            scope.launch { notice = withContext(Dispatchers.IO) { exportComments(context, app, holder.openSession(), uri) } }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        PickerStop.disarm()
        if (uri != null) {
            scope.launch { notice = withContext(Dispatchers.IO) { importComments(context, app, holder.openSession(), uri) } }
        }
    }
    // A device without the picker reports it under the rows, and the launch is undone, so the idle countdown is not left waiting for it.
    fun openPicker(launch: () -> Unit) {
        PickerStop.arm()
        try {
            launch()
        } catch (e: ActivityNotFoundException) {
            PickerStop.disarm()
            notice = NO_PICKER
        }
    }
    CommentBackupRows(
        onExport = { openPicker { exportLauncher.launch(EXPORT_NAME) } },
        onImport = { openPicker { importLauncher.launch(arrayOf("*/*")) } },
        notice = notice,
    )
}

/** The MIME type and the suggested name of an export. The file is binary, sealed under the vault's key. */
private const val EXPORT_TYPE = "application/octet-stream"
private const val EXPORT_NAME = "lumio-comments.bin"

/** The notice when the device has no system file picker to export to or import from. */
private const val NO_PICKER = "This device has no file picker, so the comments cannot be exported or imported."

/** Writes the sealed comments to [uri]. Returns the notice to show. */
private suspend fun exportComments(context: Context, app: LumioApp, session: VaultSession?, uri: Uri): String {
    val vault = app.lumioVault()
    if (session == null || vault == null) return "Unlock Lumio to export the comments."
    return try {
        val sealed = TxCommentTransfer.export(vault.txs, TxKeys.from(session))
        val stream = context.contentResolver.openOutputStream(uri) ?: return "The file could not be written."
        stream.use { it.write(sealed) }
        "The comments are exported."
    } catch (e: IOException) {
        "The file could not be written."
    }
}

/** Reads the comments of the file at [uri] into the vault. Returns the notice to show. */
private suspend fun importComments(context: Context, app: LumioApp, session: VaultSession?, uri: Uri): String {
    val vault = app.lumioVault()
    if (session == null || vault == null) return "Unlock Lumio to import the comments."
    return try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return "The file could not be read."
        val applied = TxCommentTransfer.restore(vault.txs, TxKeys.from(session), bytes)
        if (applied == 0) "The comments here are already up to date." else "Restored $applied comments."
    } catch (e: CommentImportException) {
        e.message.orEmpty()
    } catch (e: IOException) {
        "The file could not be read."
    }
}

/** Opens [url] in a browser. With no browser installed, the owner is told so. */
private fun openExplorer(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No browser is installed.", Toast.LENGTH_SHORT).show()
    }
}

/** The plan of the Transactions tab for [mode]'s side, read from the open vault. */
internal fun planOf(app: LumioApp, session: VaultSession, mode: NetworkMode): TxSyncPlan {
    val settings = EvmSettingsStore.read(session)
    return TxSync.plan(
        chains = EvmCatalog.chains(app.registry, settings),
        settings = settings,
        mode = mode,
        wallets = PairedWallets.list(session),
        bitcoin = bitcoinRefs(session),
    )
}
