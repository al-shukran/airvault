package app.lensly.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.core.vault.RealClock
import app.lensly.ui.flow.ClearReason
import app.lensly.ui.flow.RevealFlow
import app.lensly.ui.flow.RevealStep
import app.lensly.ui.flow.VaultRevealPorts
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.wallet.ShowSecretScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often the countdown on the Show Secret screen is updated. */
private const val COUNTDOWN_TICK_MS = 1_000L

/**
 * Show Secret, in the hidden graph. The owner enters the App Password again through the gate's unlock, reads a warning,
 * and then sees the secret one page at a time. The secret is cleared when the countdown ends, when the owner leaves, when
 * the app leaves the foreground, and when this screen goes away. Lock still takes the owner to the camera.
 */
@Composable
internal fun ShowSecretRoute(navController: NavHostController, holder: VaultHolder, walletId: String) {
    if (rememberOpenSession(navController, holder) == null) return
    val scope = rememberCoroutineScope()
    val password = remember { PasswordBuffer() }
    val flow = remember(walletId) { RevealFlow(VaultRevealPorts(holder, walletId)) { RealClock.elapsedMillis() } }
    val step by flow.state.collectAsState()
    var length by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val awaitingPassword = (step as? RevealStep.Password)?.busy == false

    BackHandler { flow.leave(ClearReason.DONE) }
    DisposableEffect(lifecycleOwner, flow) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                password.clear()
                flow.leave(ClearReason.LOCKED)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            password.clear()
            flow.leave(ClearReason.LOCKED)
        }
    }
    LaunchedEffect(flow) {
        while (true) {
            delay(COUNTDOWN_TICK_MS)
            flow.tick()
        }
    }
    // A lock has its own navigation, so LOCKED does nothing here.
    LaunchedEffect(step) {
        when ((step as? RevealStep.Cleared)?.reason) {
            ClearReason.TIMEOUT, ClearReason.DONE -> navController.popBackStack()
            ClearReason.VAULT_CLOSED -> navController.openHidden(HiddenRoute.GATE)
            ClearReason.UNAVAILABLE -> navController.openHidden(HiddenRoute.UNAVAILABLE)
            ClearReason.LOCKED, null -> Unit
        }
    }

    ShowSecretScreen(
        step = step,
        passwordLength = length,
        onKey = { c ->
            if (awaitingPassword && password.append(c)) length = password.length
        },
        onBackspace = {
            if (awaitingPassword) {
                password.backspace()
                length = password.length
            }
        },
        onSubmit = submit@{
            if (!awaitingPassword || password.isEmpty()) return@submit
            val entered = password.toCharArray()
            password.clear()
            length = 0
            scope.launch { flow.submitPassword(entered) }
        },
        onShow = { scope.launch { flow.show() } },
        onMove = { delta -> flow.move(delta) },
        onSelect = { part -> flow.select(part) },
        onDone = { flow.leave(ClearReason.DONE) },
        onLock = {
            flow.leave(ClearReason.LOCKED)
            leaveHidden(navController, holder)
        },
    )
}
