package app.lumio

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.composable
import app.lensly.ui.navigation.CoverNavHost
import app.lensly.ui.navigation.HiddenBackdrop
import app.lensly.ui.navigation.hiddenArea
import app.lensly.ui.navigation.leaveHidden
import app.lumio.pair.Pairing

/** Lumio's own routes inside its Hidden Area. The gate's routes are shared, see `app.lensly.ui.navigation.GateRoutes`. */
internal object LumioRoute {
    const val HOME = "home"
    const val PAIR_SCAN = "pair-scan"
    const val PAIR_REVIEW = "pair-review"
}

/**
 * The pairing that a scan has read and the owner has not saved yet. It holds public data only, and it is dropped on save, on cancel and on
 * lock.
 */
internal class PairDraft {
    var pairing by mutableStateOf<Pairing?>(null)

    fun clear() {
        pairing = null
    }
}

/**
 * Lumio's navigation: the camera cover and the gate that Lensly shares, around the companion's Hidden Area. The Hidden Area opens on the
 * gate and then shows Home. From Home the owner pairs a wallet from Lensly, through the scan and the review, and each paired wallet can be
 * deleted from Home.
 *
 * [debugHiddenRequest] counts debug launches that asked for the hidden area. It is always zero in release builds.
 */
@Composable
fun LumioNavHost(debugHiddenRequest: Int = 0, modifier: Modifier = Modifier) {
    val draft = remember { PairDraft() }
    // Counts the saves and deletions, so Home reads the paired wallets again.
    var listVersion by remember { mutableIntStateOf(0) }
    CoverNavHost(debugHiddenRequest = debugHiddenRequest, modifier = modifier) { navController, holder ->
        fun lock() {
            draft.clear()
            leaveHidden(navController, holder)
        }
        hiddenArea(navController, holder, home = LumioRoute.HOME) {
            composable(LumioRoute.HOME) {
                BackHandler { lock() }
                HiddenBackdrop {
                    HomeRoute(
                        navController = navController,
                        holder = holder,
                        listVersion = listVersion,
                        onChanged = { listVersion++ },
                        onLock = ::lock,
                    )
                }
            }
            composable(LumioRoute.PAIR_SCAN) {
                PairScanRoute(navController = navController, draft = draft)
            }
            composable(LumioRoute.PAIR_REVIEW) {
                BackHandler {
                    draft.clear()
                    navController.popBackStack(LumioRoute.HOME, inclusive = false)
                }
                HiddenBackdrop {
                    PairReviewRoute(
                        navController = navController,
                        holder = holder,
                        draft = draft,
                        onChanged = { listVersion++ },
                    )
                }
            }
        }
    }
}
