package app.lumio

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.composable
import app.lensly.ui.navigation.CoverNavHost
import app.lensly.ui.navigation.HiddenBackdrop
import app.lensly.ui.navigation.hiddenArea
import app.lensly.ui.navigation.leaveHidden
import app.lumio.ui.home.LumioHome

/** Lumio's own routes inside its Hidden Area. The gate's routes are shared, see `app.lensly.ui.navigation.GateRoutes`. */
internal object LumioRoute {
    const val HOME = "home"
}

/**
 * Lumio's navigation: the camera cover and the gate that Lensly shares, around the companion's Hidden Area. The Hidden Area opens on the
 * gate and then shows the companion's Home. Lumio has no other screens yet.
 *
 * [debugHiddenRequest] counts debug launches that asked for the hidden area. It is always zero in release builds.
 */
@Composable
fun LumioNavHost(debugHiddenRequest: Int = 0, modifier: Modifier = Modifier) {
    CoverNavHost(debugHiddenRequest = debugHiddenRequest, modifier = modifier) { navController, holder ->
        hiddenArea(navController, holder, home = LumioRoute.HOME) {
            composable(LumioRoute.HOME) {
                BackHandler { leaveHidden(navController, holder) }
                HiddenBackdrop {
                    LumioHome(onLock = { leaveHidden(navController, holder) })
                }
            }
        }
    }
}
