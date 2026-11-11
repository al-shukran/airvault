package app.lensly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.ui.flow.DeleteWalletPorts
import app.lensly.ui.flow.RemoveDuressPorts
import app.lensly.ui.flow.ShredFlow
import app.lensly.ui.flow.ShredOutcome
import app.lensly.ui.flow.ShredStep
import app.lensly.ui.flow.WipeVaultPorts
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.wallet.ShredCopy
import app.lensly.ui.wallet.ShredScreen
import kotlinx.coroutines.launch

/**
 * Delete Wallet, in the hidden graph, from the wallet detail. The owner enters the App Password again, reads the warning, and
 * confirms. The wallet's Per-Wallet Key goes first, so the wallet cannot be read afterwards, and the wallet list comes back.
 */
@Composable
internal fun DeleteWalletRoute(navController: NavHostController, holder: VaultHolder, walletId: String) {
    rememberOpenSession(navController, holder) ?: return
    val flow = remember(walletId) { ShredFlow(DeleteWalletPorts(holder, walletId)) }
    ShredRoute(
        navController = navController,
        holder = holder,
        flow = flow,
        copy = DELETE_WALLET_COPY,
        onErased = { navController.popBackStack(HiddenRoute.HOME, inclusive = false) },
    )
}

/**
 * Wipe, from Settings. The owner enters the App Password again, reads the warning, and confirms. The vault locks, every file
 * and key is removed, and the gate then sets up a new App Password, as on a fresh install. This screen does not read the
 * session, because the wipe closes it on purpose.
 */
@Composable
internal fun WipeRoute(navController: NavHostController, holder: VaultHolder) {
    val flow = remember { ShredFlow(WipeVaultPorts(holder)) }
    ShredRoute(
        navController = navController,
        holder = holder,
        flow = flow,
        copy = WIPE_COPY,
        onErased = { navController.openHidden(HiddenRoute.GATE) },
    )
}

/**
 * The shared screen of a destructive action. It collects the App Password as Show Secret does, through [PasswordBuffer]. Each
 * outcome of the erase leaves the screen: erased runs [onErased], a lock returns to the camera, and a vault that cannot be
 * changed shows the neutral screen.
 */
/**
 * Remove the Duress Password, from Settings. The owner enters the App Password again, reads the warning, and confirms. The
 * Duress Password stops opening anything, and the wallets it opened are erased. The real vault's wallets stay.
 */
@Composable
internal fun RemoveDuressRoute(navController: NavHostController, holder: VaultHolder) {
    val flow = remember { ShredFlow(RemoveDuressPorts(holder)) }
    ShredRoute(
        navController = navController,
        holder = holder,
        flow = flow,
        copy = REMOVE_DURESS_COPY,
        onErased = { navController.popBackStack() },
    )
}

@Composable
internal fun ShredRoute(
    navController: NavHostController,
    holder: VaultHolder,
    flow: ShredFlow,
    copy: ShredCopy,
    onErased: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val password = remember { PasswordBuffer() }
    val step by flow.state.collectAsState()
    var length by remember { mutableIntStateOf(0) }
    val awaitingPassword = (step as? ShredStep.Password)?.busy == false

    DisposableEffect(flow) {
        onDispose { password.clear() }
    }
    LaunchedEffect(step) {
        when ((step as? ShredStep.Ended)?.outcome) {
            ShredOutcome.ERASED -> onErased()
            ShredOutcome.LOCKED -> leaveHidden(navController, holder)
            ShredOutcome.UNAVAILABLE -> navController.openHidden(HiddenRoute.UNAVAILABLE)
            null -> Unit
        }
    }

    ShredScreen(
        step = step,
        copy = copy,
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
        onConfirm = { scope.launch { flow.confirm() } },
        onBack = { navController.popBackStack() },
        onLock = { leaveHidden(navController, holder) },
    )
}

private val DELETE_WALLET_COPY = ShredCopy(
    title = "Delete wallet",
    prompt = "Enter your App Password to delete this wallet.",
    warningTitle = "Cannot be recovered",
    warning = "This wallet and its accounts are erased from this device. It cannot be recovered without its backup: " +
        "the recovery phrase and passphrase, or the single key. Check that the backup is safe before you delete.",
    action = "Delete wallet",
)

private val WIPE_COPY = ShredCopy(
    title = "Wipe",
    prompt = "Enter your App Password to wipe the vault.",
    warningTitle = "Erases everything",
    warning = "Every wallet, every account and the App Password are erased from this device. Only the backups of " +
        "your wallets can bring them back. The app then asks for a new App Password.",
    action = "Wipe",
)

private val REMOVE_DURESS_COPY = ShredCopy(
    title = "Duress Password",
    prompt = "Enter your App Password to remove the Duress Password.",
    warningTitle = "Cannot be recovered",
    warning = "The Duress Password stops opening anything, and the wallets it opened are erased from this device. " +
        "Your wallets stay.",
    action = "Remove",
)
