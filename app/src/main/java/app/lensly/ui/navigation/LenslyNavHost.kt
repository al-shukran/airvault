package app.lensly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Lensly's navigation: the camera cover and the gate that Lumio shares, around Lensly's own hidden area. See [CoverNavHost].
 *
 * [debugHiddenRequest] counts debug launches that asked for the hidden area. It is always zero in release builds.
 */
@Composable
fun LenslyNavHost(debugHiddenRequest: Int = 0, modifier: Modifier = Modifier) {
    CoverNavHost(debugHiddenRequest = debugHiddenRequest, modifier = modifier) { navController, holder ->
        hiddenGraph(navController, holder)
    }
}
