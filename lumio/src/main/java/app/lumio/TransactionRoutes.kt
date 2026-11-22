package app.lumio

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.lensly.app.addressBookEntryFor
import app.lensly.app.canonicalAddressOn
import app.lensly.app.chainOfAddress
import app.lensly.core.vault.VaultSession
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.PickerStop
import app.lensly.ui.navigation.openHidden
import app.lumio.data.tx.CommentImportException
import app.lumio.data.tx.TxCommentTransfer
import app.lumio.data.tx.TxKeys
import app.lumio.data.tx.TxQuery
import app.lumio.data.tx.TxStore
import app.lumio.pair.PairedWallet
import app.lumio.portfolio.TransactionPage
import app.lumio.ui.settings.CommentBackupRows
import app.lumio.ui.transactions.AddressInfoSheet
import app.lumio.ui.transactions.TransactionDetailScreen
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * The page of one transaction, with the address sheet of an address opened over it. The row that opens the page has read the transaction already, so the
 * first frame has it. A transaction the store does not hold leaves the screen.
 */
@Composable
internal fun TransactionDetailRoute(navController: NavHostController, holder: VaultHolder, walletId: String, account: String, txId: String, onLock: () -> Unit) {
    val app = LocalContext.current.applicationContext as LumioApp
    val context = LocalContext.current
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val vault = app.lumioVault()
    val board = vault?.portfolio
    val display = rememberDisplay(app)
    val load by produceState<DetailLoad?>(
        initialValue = board?.transactionOf(account, txId)?.let { DetailLoad.Ready(it) },
        walletId,
        account,
        txId,
    ) {
        if (value == null) {
            value = board?.readTransaction(walletId, account, txId)?.let { DetailLoad.Ready(it) } ?: DetailLoad.Missing
        }
    }
    when (val current = load) {
        null -> return
        DetailLoad.Missing -> {
            LaunchedEffect(Unit) { navController.popBackStack() }
            return
        }
        is DetailLoad.Ready -> {
            val page = current.page
            var sheet by remember { mutableStateOf<String?>(null) }
            TransactionDetailScreen(
                detail = page.detail,
                accountNames = page.accounts.mapValues { (_, own) -> own.displayName ?: own.autoName },
                book = page.book,
                backLabel = navController.backLabel(),
                showBalance = display.showAssets,
                onAddress = { sheet = it },
                onExplorer = { page.detail.explorerUrl?.let { openExplorer(context, it) } },
                onBack = { navController.popBackStack() },
                onLock = onLock,
            )
            sheet?.let { address ->
                val wallet = page.wallet
                val seenIn by produceState<Int?>(null, address) {
                    value = withContext(Dispatchers.IO) { vault?.txs?.let { seenInCount(it, wallet, address) } }
                }
                AddressInfoSheet(
                    address = address,
                    entry = chainOfAddress(address)?.let { addressBookEntryFor(address, it, page.book) },
                    walletName = wallet?.name,
                    seenIn = seenIn,
                    onEdit = {
                        sheet = null
                        navController.navigate(LumioRoute.addressHandoff(address, edit = true))
                    },
                    onAdd = {
                        sheet = null
                        navController.navigate(LumioRoute.addressHandoff(address, edit = false))
                    },
                    onClose = { sheet = null },
                )
            }
        }
    }
}

/** The state of [TransactionDetailRoute]: the transaction page, or missing when the store does not hold the transaction. */
private sealed interface DetailLoad {
    data object Missing : DetailLoad

    class Ready(val page: TransactionPage) : DetailLoad
}

/**
 * The transactions of [wallet] whose counterparty is [address], spam included, or null when there is no wallet. The store finds the rows that hold the
 * address's words across the wallet's accounts on every chain, and the counterparty of each row is then compared with the address (see [isSameAddress]).
 */
internal suspend fun seenInCount(txs: TxStore, wallet: PairedWallet?, address: String): Int? {
    if (wallet == null) return null
    val query = TxQuery(accounts = ownedAddressesOf(wallet).toSet(), text = address, includeSpam = true)
    return txs.page(query, 0, Int.MAX_VALUE).count { isSameAddress(it.counterparty, address) }
}

/**
 * Whether [counterparty] is [address], compared in the canonical form of the chain that [address] is on: Ethereum addresses case-insensitively, the
 * other chains exactly. A counterparty that is no address of that chain, or none, is not [address].
 */
internal fun isSameAddress(counterparty: String?, address: String): Boolean {
    val chain = chainOfAddress(address) ?: return false
    return counterparty != null && canonicalAddressOn(counterparty, chain) == canonicalAddressOn(address, chain)
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
