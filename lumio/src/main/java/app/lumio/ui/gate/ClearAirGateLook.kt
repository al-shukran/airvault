package app.lumio.ui.gate

import androidx.compose.runtime.Composable
import app.lensly.ui.navigation.GateLook
import app.lensly.ui.navigation.UnlockUi
import app.lensly.ui.theme.ClearAirTheme

/** Lumio's gate in Clear Air, as the rest of Lumio is drawn. Each page sets its own theme, so the gate draws the same whatever its route. */
object ClearAirGateLook : GateLook {
    @Composable
    override fun Unlock(state: UnlockUi) = ClearAirTheme { LumioUnlockScreen(state) }

    @Composable
    override fun Unavailable(detail: String?, onClose: () -> Unit) = ClearAirTheme { LumioUnavailableScreen(detail, onClose) }
}
