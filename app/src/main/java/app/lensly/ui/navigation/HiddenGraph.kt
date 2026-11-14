package app.lensly.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import app.lensly.app.RegistryHolder
import app.lensly.app.VaultHolder
import app.lensly.app.WalletDraft
import app.lensly.app.WalletDraftViewModel
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.PasswordPolicy
import app.lensly.core.vault.PolicyReason
import app.lensly.core.vault.PolicyResult
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.VaultException
import app.lensly.ui.gate.CreatePasswordScreen
import app.lensly.ui.gate.CreatePasswordState
import app.lensly.ui.gate.CreateStep
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.gate.PolicyError
import app.lensly.ui.gate.UnavailableScreen
import app.lensly.ui.gate.UnlockScreen
import app.lensly.ui.theme.LenslyBlack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The hidden area: a nested graph that opens on the gate. The gate sends the owner to the screen that fits the
 * vault's state. The wallet screens are in [walletRoutes].
 */
internal fun NavGraphBuilder.hiddenGraph(navController: NavHostController, holder: VaultHolder) {
    navigation(startDestination = HiddenRoute.GATE, route = LenslyRoute.HIDDEN) {
        composable(HiddenRoute.GATE) { HiddenBackdrop { GateRoute(navController, holder) } }
        composable(HiddenRoute.CREATE_PASSWORD) { HiddenBackdrop { CreatePasswordRoute(navController, holder) } }
        composable(HiddenRoute.UNLOCK) { HiddenBackdrop { UnlockRoute(navController, holder) } }
        composable(HiddenRoute.UNAVAILABLE) {
            HiddenBackdrop {
                UnavailableScreen(onClose = { leaveHidden(navController, holder) }, detail = holder.vault.refusal())
            }
        }
        walletRoutes(navController, holder)
        settingsRoutes(navController, holder)
    }
}

/**
 * Paints an opaque black backdrop behind a hidden screen. The camera stays composed underneath the hidden graph,
 * so without this its viewfinder or permission prompt would show through.
 */
@Composable
internal fun HiddenBackdrop(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(LenslyBlack)) {
        content()
    }
}

/**
 * Locks the vault and returns to the camera. Popping the hidden graph also clears its [WalletDraft], so no secret
 * outlives the visit.
 */
internal fun leaveHidden(navController: NavHostController, holder: VaultHolder) {
    holder.lock()
    navController.navigate(LenslyRoute.CAMERA) {
        popUpTo(LenslyRoute.CAMERA) { inclusive = false }
        launchSingleTop = true
    }
}

/**
 * The system document picker runs in its own activity, so this app stops while it is open. [arm] is called before a
 * picker launch, and the next stop it causes does not lock the vault, or the import could never finish. [takeStop]
 * is consulted on every stop and disarms at once, so any later stop locks as usual. [disarm] runs when the picker
 * returns, in case its stop was never seen.
 */
internal object PickerStop {
    private var armed = false

    @Synchronized
    fun arm() {
        armed = true
    }

    @Synchronized
    fun disarm() {
        armed = false
    }

    /** True once after [arm], for the stop that the picker causes. */
    @Synchronized
    fun takeStop(): Boolean = armed.also { armed = false }
}

/** Moves within the hidden graph. The gate and password screens behind the new screen are removed. */
internal fun NavHostController.openHidden(route: String) {
    navigate(route) {
        popUpTo(LenslyRoute.HIDDEN) { inclusive = false }
    }
}

/** The draft shared by every screen of the hidden graph. It is scoped to the graph, so rotation keeps it. */
@Composable
internal fun draftOf(navController: NavHostController, entry: NavBackStackEntry): WalletDraft {
    // Keyed on this screen's own entry, which stays the same through the exit fade. Leaving the hidden area pops the
    // graph, and resolving its entry again there would throw. Entering the hidden area again composes the screen
    // afresh, with a new entry, so the graph's new owner is picked up then.
    val owner = remember(entry) { navController.getBackStackEntry(LenslyRoute.HIDDEN) }
    return viewModel<WalletDraftViewModel>(viewModelStoreOwner = owner).draft
}

/** The outcome of creating or unlocking the vault from a screen. */
private enum class GateOutcome { Opened, Rejected, Unavailable }

@Composable
private fun GateRoute(navController: NavHostController, holder: VaultHolder) {
    LaunchedEffect(Unit) {
        val next = when {
            holder.openSession() != null -> HiddenRoute.HOME
            holder.vault.exists() -> HiddenRoute.UNLOCK
            // A device that may not hold a vault is told so before the owner types a password for nothing.
            withContext(Dispatchers.IO) { holder.vault.canCreate() } -> HiddenRoute.CREATE_PASSWORD
            else -> HiddenRoute.UNAVAILABLE
        }
        navController.navigate(next) {
            popUpTo(HiddenRoute.GATE) { inclusive = true }
        }
    }
}

@Composable
private fun CreatePasswordRoute(navController: NavHostController, holder: VaultHolder) {
    val scope = rememberCoroutineScope()
    val buffer = remember { PasswordBuffer() }
    var first by remember { mutableStateOf<CharArray?>(null) }
    var state by remember { mutableStateOf(CreatePasswordState()) }
    var busy by remember { mutableStateOf(false) }

    BackHandler { leaveHidden(navController, holder) }
    DisposableEffect(Unit) {
        onDispose {
            buffer.clear()
            first?.fill('\u0000')
        }
    }

    CreatePasswordScreen(
        state = state,
        busy = busy,
        onKey = { c ->
            if (!busy && buffer.append(c)) state = state.copy(length = buffer.length, error = null)
        },
        onBackspace = {
            if (!busy) {
                buffer.backspace()
                state = state.copy(length = buffer.length, error = null)
            }
        },
        onSubmit = submit@{
            if (busy) return@submit
            if (state.step == CreateStep.Enter) {
                val entered = buffer.toCharArray()
                buffer.clear()
                when (val policy = PasswordPolicy.check(entered)) {
                    is PolicyResult.Refused -> {
                        entered.fill('\u0000')
                        state = CreatePasswordState(step = CreateStep.Enter, error = policy.reason.toPolicyError())
                    }
                    PolicyResult.Accepted -> {
                        first?.fill('\u0000')
                        first = entered
                        state = CreatePasswordState(step = CreateStep.Confirm)
                    }
                }
            } else {
                val second = buffer.toCharArray()
                buffer.clear()
                val expected = first
                val matches = expected != null && expected.contentEquals(second)
                second.fill('\u0000')
                if (expected == null || !matches) {
                    state = CreatePasswordState(step = CreateStep.Confirm, error = PolicyError.Mismatch)
                } else {
                    first = null
                    busy = true
                    scope.launch {
                        val outcome = createVault(holder, expected)
                        busy = false
                        if (outcome == GateOutcome.Opened) {
                            navController.openHidden(HiddenRoute.HOME)
                        } else {
                            navController.openHidden(HiddenRoute.UNAVAILABLE)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun UnlockRoute(navController: NavHostController, holder: VaultHolder) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val buffer = remember { PasswordBuffer() }
    var length by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    BackHandler { leaveHidden(navController, holder) }
    DisposableEffect(Unit) {
        onDispose { buffer.clear() }
    }

    UnlockScreen(
        length = length,
        busy = busy,
        onKey = { c ->
            if (!busy && buffer.append(c)) length = buffer.length
        },
        onBackspace = {
            if (!busy) {
                buffer.backspace()
                length = buffer.length
            }
        },
        onSubmit = submit@{
            if (busy || buffer.isEmpty()) return@submit
            val password = buffer.toCharArray()
            buffer.clear()
            length = 0
            busy = true
            // Home shows the catalog's counts, so the catalog loads while the key is derived.
            scope.launch(Dispatchers.IO) { runCatching { RegistryHolder.get(context) } }
            scope.launch {
                val outcome = unlockWith(holder, password)
                busy = false
                when (outcome) {
                    GateOutcome.Opened -> navController.openHidden(HiddenRoute.HOME)
                    // A wrong password clears the field and says nothing. The field was cleared on submit.
                    GateOutcome.Rejected -> Unit
                    GateOutcome.Unavailable -> navController.openHidden(HiddenRoute.UNAVAILABLE)
                }
            }
        },
    )
}

/**
 * Creates the vault with [password], then opens it with the same password. [app.lensly.core.vault.Vault.create]
 * zeroes the array it is given, so it gets a copy. The original is zeroed here, on every path.
 */
private suspend fun createVault(holder: VaultHolder, password: CharArray): GateOutcome = try {
    if (holder.vault.create(password.copyOf()) != CreateResult.Created) {
        GateOutcome.Unavailable
    } else {
        when (val outcome = unlockWith(holder, password)) {
            GateOutcome.Rejected -> GateOutcome.Unavailable
            else -> outcome
        }
    }
} catch (e: VaultException) {
    GateOutcome.Unavailable
} finally {
    password.fill('\u0000')
}

/**
 * Unlocks with [password], which the vault zeroes, and keeps the session if the vault was not locked meanwhile.
 * A damaged vault gives [GateOutcome.Unavailable].
 */
private suspend fun unlockWith(holder: VaultHolder, password: CharArray): GateOutcome {
    val epoch = holder.unlockEpoch()
    return try {
        when (val result = holder.vault.unlock(password)) {
            is UnlockResult.Unlocked ->
                if (holder.adopt(result.session, epoch)) GateOutcome.Opened else GateOutcome.Rejected
            UnlockResult.Rejected -> GateOutcome.Rejected
        }
    } catch (e: VaultException) {
        GateOutcome.Unavailable
    }
}

internal fun PolicyReason.toPolicyError(): PolicyError = when (this) {
    PolicyReason.TOO_SHORT -> PolicyError.TooShort
    PolicyReason.WEAK_SCORE, PolicyReason.LOW_ENTROPY -> PolicyError.TooWeak
}
