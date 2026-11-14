package app.lumio.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton

/**
 * Lumio's Home, the companion's first screen. No wallet is paired yet, and pairing comes from Lensly, which is not built yet, so the
 * pairing action is shown disabled. [onLock] returns to the camera and locks the vault.
 */
@Composable
fun LumioHome(onLock: () -> Unit, modifier: Modifier = Modifier) {
    LenslyScreen(
        title = "Lumio",
        modifier = modifier,
        onBack = onLock,
        scrollable = false,
        bottomAction = { PillButton(text = "Pair from Lensly", onClick = {}, enabled = false) },
    ) {
        EmptyState(
            icon = LenslyIcons.Wallets,
            title = "No wallets paired yet",
            body = "Pair a wallet from Lensly to see its balances and history here.",
        )
    }
}
