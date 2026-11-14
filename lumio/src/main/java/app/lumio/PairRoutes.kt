package app.lumio

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.HiddenBackdrop
import app.lensly.ui.navigation.openHidden
import app.lumio.pair.PairScan
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.pair.ScanStep
import app.lumio.ui.home.LumioHome
import app.lumio.ui.pair.PairReviewScreen
import app.lumio.ui.pair.PairScanScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SCAN_HINT = "Point the camera at the pairing code that Lensly shows."
private const val SCAN_READING = "Reading the code. Keep the camera on it."
private const val SAVE_FAILED = "The pairing could not be saved. Try again."

/** The pause between the frames of the debug fixture, long enough for the progress to show. */
private const val FIXTURE_FRAME_MS = 350L

/** Home: the paired wallets of the open vault, and the actions that pair or delete one. */
@Composable
internal fun HomeRoute(
    navController: NavHostController,
    holder: VaultHolder,
    listVersion: Int,
    onChanged: () -> Unit,
    onLock: () -> Unit,
) {
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    val scope = rememberCoroutineScope()
    val wallets by produceState<List<PairedWallet>>(initialValue = emptyList(), session, listVersion) {
        value = if (session == null) emptyList() else onVault(emptyList()) { PairedWallets.list(session) }
    }
    LumioHome(
        onLock = onLock,
        onPair = { navController.openHidden(LumioRoute.PAIR_SCAN) },
        wallets = wallets,
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

/**
 * The scan for a pairing code. Each frame goes through [PairScan]. A complete pairing opens the review, and a refused code is reported
 * before the scan starts again.
 */
@Composable
internal fun PairScanRoute(navController: NavHostController, draft: PairDraft) {
    val activity = LocalContext.current as? Activity
    var scan by remember { mutableStateOf(PairScan()) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var status by remember { mutableStateOf(SCAN_HINT) }
    var finished by remember { mutableStateOf(false) }

    fun onText(text: String) {
        if (finished) return
        when (val step = scan.onText(text)) {
            is ScanStep.Reading -> {
                progress = step.fraction
                status = SCAN_READING
            }
            is ScanStep.Complete -> {
                finished = true
                draft.pairing = step.pairing
                navController.navigate(LumioRoute.PAIR_REVIEW) {
                    popUpTo(LumioRoute.PAIR_SCAN) { inclusive = true }
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
    BackHandler { navController.popBackStack() }
    HiddenBackdrop {
        PairScanScreen(
            progress = progress,
            status = status,
            onText = ::onText,
            onCancel = { navController.popBackStack() },
        )
    }
}

/** The review of the pairing that the scan read. Saving stores it, and the owner returns to Home. */
@Composable
internal fun PairReviewRoute(
    navController: NavHostController,
    holder: VaultHolder,
    draft: PairDraft,
    onChanged: () -> Unit,
) {
    val session = holder.openSession()
    val pairing = draft.pairing
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
        LaunchedEffect(Unit) { navController.popBackStack(LumioRoute.HOME, inclusive = false) }
        return
    }
    PairReviewScreen(
        pairing = pairing,
        alreadyPaired = alreadyPaired,
        error = error,
        onSave = {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching { PairedWallets.save(session, pairing, System.currentTimeMillis()) }.getOrNull()
                }
                if (saved == null) {
                    error = SAVE_FAILED
                } else {
                    draft.clear()
                    onChanged()
                    navController.popBackStack(LumioRoute.HOME, inclusive = false)
                }
            }
        },
        onCancel = {
            draft.clear()
            navController.popBackStack(LumioRoute.HOME, inclusive = false)
        },
    )
}

/**
 * Runs [block] off the main thread, and returns [default] when it cannot read: the lock may have closed the session under it, and a Home
 * that shows nothing is better than a crash.
 */
private suspend fun <T> onVault(default: T, block: () -> T): T = withContext(Dispatchers.IO) {
    try {
        block()
    } catch (e: Exception) {
        default
    }
}
