package app.lumio

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import app.lensly.core.vault.VaultSession
import app.lumio.data.config.FinishItem
import app.lumio.data.config.FinishSetupStore
import app.lumio.home.HomeLoader
import app.lumio.network.NetworkMode
import app.lumio.pair.FirstSync
import app.lumio.pair.FirstSyncImport
import app.lumio.pair.FirstSyncImports
import app.lumio.pair.PairScan
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.pair.ScanStep
import app.lumio.pair.SyncRow
import app.lumio.pair.SyncStep
import app.lumio.ui.pair.FirstSyncScreen
import app.lumio.ui.pair.PairReviewScreen
import app.lumio.ui.pair.PairScanScreen
import app.lumio.wallets.WalletsLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SCAN_HINT = "Point the camera at the pairing code that Lensly shows."
private const val SCAN_READING = "Reading the code. Keep the camera on it."
private const val SCAN_STALLED = "Move a little closer, or tilt to cut glare."

/** How long a scan waits for a new part before it asks the owner to move closer (section 2.1). */
private const val STALL_AFTER_MS = 6_000L

/** The pause after the last part, while the ring closes and the check shows, before the review opens (section 2.1). */
private const val COMPLETE_MS = 350L

/** The line under the viewfinder while an animated code is read: how many of its parts are in, against the number that it declares. */
private fun readingStatus(received: Int, expected: Int): String =
    if (expected <= 0) SCAN_READING else "${minOf(received, expected)} of about $expected parts · hold steady"
private const val SAVE_FAILED = "The pairing could not be saved. Try again."

/** The pause between the frames of the debug fixture, long enough for the progress to show. */
private const val FIXTURE_FRAME_MS = 350L

/**
 * The scan for a pairing code. Each frame goes through [PairScan]. A complete pairing opens the review, and a refused code is reported
 * before the scan starts again.
 */
@Composable
internal fun PairScanRoute(navController: NavHostController, draft: PairDraft) {
    val activity = LocalActivity.current
    var scan by remember { mutableStateOf(PairScan()) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var status by remember { mutableStateOf(SCAN_HINT) }
    var finished by remember { mutableStateOf(false) }
    var complete by remember { mutableStateOf(false) }
    var parts by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun onText(text: String) {
        if (finished) return
        when (val step = scan.onText(text)) {
            is ScanStep.Reading -> {
                progress = step.fraction
                status = readingStatus(step.received, step.expected)
                parts++
            }
            is ScanStep.Complete -> {
                finished = true
                draft.pairing = step.pairing
                progress = 1f
                complete = true
                // The ring closes and the check shows for a moment before the review opens (section 2.1).
                scope.launch {
                    delay(COMPLETE_MS)
                    navController.navigate(LumioRoute.PAIR_REVIEW) {
                        popUpTo(LumioRoute.PAIR_SCAN) { inclusive = true }
                    }
                }
            }
            is ScanStep.Refused -> {
                scan = PairScan()
                progress = null
                status = step.reason
            }
            ScanStep.Ignored -> Unit
        }
    }

    // A debug build can read a fixture in place of the camera, so the flow can be shown without one. Release builds have no fixture.
    LaunchedEffect(Unit) {
        DebugPairFixture.partsFor(activity?.intent)?.forEach { part ->
            delay(FIXTURE_FRAME_MS)
            onText(part)
        }
    }
    // Each new part restarts the wait: when none comes for a while, the code is probably out of focus or in glare.
    LaunchedEffect(parts) {
        delay(STALL_AFTER_MS)
        if (!finished) status = SCAN_STALLED
    }
    BackHandler { navController.popBackStack() }
    PairScanScreen(
        progress = progress,
        status = status,
        complete = complete,
        onText = ::onText,
        onCancel = { navController.popBackStack() },
    )
}

/** The review of the pairing that the scan read. Saving stores it, and the owner returns to Home. */
@Composable
internal fun PairReviewRoute(
    navController: NavHostController,
    holder: VaultHolder,
    draft: PairDraft,
    onChanged: () -> Unit,
    onLock: () -> Unit,
) {
    val session = holder.openSession()
    // The review keeps the pairing it opened with. Save clears the draft, and that must not send the review back to Wallets as it leaves for First sync.
    val pairing = remember { draft.pairing }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val alreadyPaired by produceState(initialValue = false, session, pairing) {
        value = if (session == null || pairing == null) {
            false
        } else {
            onVault(false) { PairedWallets.list(session).any { it.fingerprint == pairing.fingerprint } }
        }
    }
    if (session == null || pairing == null) {
        LaunchedEffect(Unit) { navController.popBackStack(LumioRoute.WALLETS, inclusive = false) }
        return
    }
    PairReviewScreen(
        pairing = pairing,
        alreadyPaired = alreadyPaired,
        error = error,
        onSave = {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching { PairedWallets.save(session, pairing, System.currentTimeMillis()) }.getOrNull()?.also {
                        runCatching { FinishSetupStore.tick(session, FinishItem.PAIR) }
                    }
                }
                if (saved == null) {
                    error = SAVE_FAILED
                } else {
                    draft.clear()
                    onChanged()
                    // The pairing replaced the wallet's records, so the import kept for its earlier pairing is dropped: the next import reads them.
                    (holder.sessionAttachment() as? LumioVault)?.firstSyncs?.drop(saved.id)
                    // The first reads of the paired wallet start now, before the owner opens it, so its balances land on the wallet screens as they arrive.
                    (holder.sessionAttachment() as? LumioVault)?.wallets?.refresh()
                    navController.navigate(LumioRoute.firstSync(saved.id)) { popUpTo(LumioRoute.WALLETS) { inclusive = false } }
                }
            }
        },
        onCancel = {
            draft.clear()
            navController.popBackStack(LumioRoute.WALLETS, inclusive = false)
        },
        onLock = onLock,
    )
}

/**
 * Runs [block] off the main thread, and returns [default] when it cannot read: the lock may have closed the session under it, and a Home
 * that shows nothing is better than a crash.
 */
internal suspend fun <T> onVault(default: T, block: suspend () -> T): T = withContext(Dispatchers.IO) {
    try {
        block()
    } catch (e: Exception) {
        default
    }
}

/**
 * The first sync of the wallet [walletId], opened when its pairing is saved. Its reads run on the application's scope, so they go on when the owner
 * leaves. This is the last step of the onboarding, so only Continue leaves it, for the wallet. View Accounts opens the wallet above this step, and
 * the system back stays here.
 */
@Composable
internal fun FirstSyncRoute(navController: NavHostController, holder: VaultHolder, walletId: String, mode: NetworkMode, onLock: () -> Unit) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    // A decoy vault keeps no Lumio state, so its import is not kept and each visit starts it again.
    val imports = (holder.sessionAttachment() as? LumioVault)?.firstSyncs ?: FirstSyncImports()
    // The import as an earlier visit left it: its rows show on the first frame, and its reads do not run again.
    var imported by remember(session, walletId, mode) { mutableStateOf(imports.get(walletId, mode)) }
    fun finish() {
        navController.popBackStack(LumioRoute.WALLETS, inclusive = false)
        navController.navigate(LumioRoute.wallet(walletId))
    }
    fun viewAccounts() {
        navController.navigate(LumioRoute.wallet(walletId)) { launchSingleTop = true }
    }
    // The system back stays on this step, so only Continue leaves the onboarding.
    BackHandler {}
    if (session == null) {
        LaunchedEffect(Unit) { navController.popBackStack(LumioRoute.WALLETS, inclusive = false) }
        return
    }
    // Each visit reads the wallet again. The vault may have lost it while the owner was away, and a wallet
    // that is gone leaves for the wallets list.
    LaunchedEffect(session, walletId, mode) {
        val wallet = onVault(null) { PairedWallets.list(session).firstOrNull { it.id == walletId } }
        if (wallet == null) {
            navController.popBackStack(LumioRoute.WALLETS, inclusive = false)
        } else {
            val sync = imports.get(walletId, mode)?.sync ?: FirstSync(firstSyncSteps(app, session, mode, wallet))
            imported = FirstSyncImport(wallet, sync).also { imports.put(walletId, mode, it) }
        }
    }
    val current = imported ?: return
    val sync = current.sync
    LaunchedEffect(sync) { app.runInAppScope { sync.run() } }
    val rows by sync.rows.collectAsState()
    val wallet = current.wallet
    val families = listOf<List<*>>(wallet.evmAddresses, wallet.bitcoin, wallet.solana, wallet.algorand)
    FirstSyncScreen(
        walletName = wallet.name,
        accounts = families.sumOf { it.size },
        chains = families.count { it.isNotEmpty() },
        rows = rows,
        onViewAccounts = ::viewAccounts,
        onFinish = ::finish,
        onLock = onLock,
    )
}

/**
 * The reads of the first sync of [wallet] on [mode]'s side, in order: the Bitcoin scans, the Ethereum balances, the Solana and Algorand balances,
 * then the prices. A row exists only for a chain that the wallet has accounts on.
 */
internal fun firstSyncSteps(app: LumioApp, session: VaultSession, mode: NetworkMode, wallet: PairedWallet): List<SyncStep> = buildList {
    if (wallet.bitcoin.isNotEmpty()) {
        add(
            SyncStep(listOf(SyncRow.BITCOIN)) {
                HomeLoader.scanBitcoin(app, session, mode)
                emptySet()
            },
        )
    }
    if (wallet.evmAddresses.isNotEmpty()) {
        add(
            SyncStep(listOf(SyncRow.EVM)) {
                if (HomeLoader.refreshEvm(app, session, mode).isEmpty()) emptySet() else setOf(SyncRow.EVM)
            },
        )
    }
    val native = listOfNotNull(
        SyncRow.SOLANA.takeIf { wallet.solana.isNotEmpty() },
        SyncRow.ALGORAND.takeIf { wallet.algorand.isNotEmpty() },
    )
    if (native.isNotEmpty()) {
        add(
            SyncStep(native) {
                HomeLoader.refreshNative(app, session, mode).map { if (it.network.isSol) SyncRow.SOLANA else SyncRow.ALGORAND }.toSet()
            },
        )
    }
    add(
        SyncStep(listOf(SyncRow.PRICES)) {
            WalletsLoader.read(app, session, mode)
            emptySet()
        },
    )
}
