package app.lensly.ui.navigation

import android.content.Context
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import app.lensly.app.VaultHolder
import app.lensly.core.vault.BiometricOutcome
import app.lensly.core.vault.BiometricUnlock
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.PasswordPolicy
import app.lensly.core.vault.PolicyReason
import app.lensly.core.vault.PolicyResult
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.VAULT_DAMAGED
import app.lensly.core.vault.VaultAuthRequiredException
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultKeyInvalidatedException
import app.lensly.core.vault.VaultKeystore
import app.lensly.ui.gate.CreatePasswordScreen
import app.lensly.ui.gate.CreatePasswordState
import app.lensly.ui.gate.BIOMETRIC_OFF_NOTICE
import app.lensly.ui.gate.CreateStep
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.gate.PolicyError
import app.lensly.ui.gate.UnavailableScreen
import app.lensly.ui.gate.UnlockScreen
import app.lensly.ui.gate.eraseWarning
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/** The gate's routes inside the hidden area. Every app's Hidden Area starts with them. */
object GateRoutes {
    /** Decides between create and unlock, and shows the neutral screen when the vault cannot open. */
    const val GATE = "gate"
    const val CREATE_PASSWORD = "create-password"
    const val UNLOCK = "unlock"
    const val UNAVAILABLE = "unavailable"

    /** Vault files without a whole vault. Nothing is created over them, so the owner clears the app's data. */
    const val DAMAGED = "damaged"

    /** The device has no screen lock, and the vault key needs one. Nothing is created until the owner sets one. */
    const val NO_SCREEN_LOCK = "no-screen-lock"

    /** Android deleted the vault key, because the screen lock was removed or changed. The owner restores from the seed phrases. */
    const val KEY_INVALIDATED = "key-invalidated"
}

private const val NO_SCREEN_LOCK_DETAIL =
    "Set a screen lock first. The vault is tied to it, so the vault cannot be created without one."

private const val KEY_INVALIDATED_DETAIL =
    "The screen lock was removed or changed, so the vault can no longer be opened. Clear the app's data, " +
        "then restore your wallets from their backups."

/**
 * The gate's two pages, unlock and unavailable, as one look. [CoverGateLook] draws the camera cover's screens, which Lensly keeps. An app
 * that wants another look passes its own to [hiddenArea]. The routes, the counts and the state stay the same, and a look only words and
 * draws them.
 */
interface GateLook {
    @Composable fun Unlock(state: UnlockUi)

    @Composable fun Unavailable(detail: String?, onClose: () -> Unit)
}

/**
 * What the unlock page shows. [refused] is true from a refused try until the next one starts, so a look can react to each refusal. [triesLeft]
 * is set when the warning is due (see [app.lensly.core.vault.FailureDelay.WARN_TRIES_LEFT]). [waitMs] is the Failure Delay still to run, counted
 * down, or null when none runs.
 */
class UnlockUi(
    val length: Int,
    val busy: Boolean,
    val refused: Boolean,
    val triesLeft: Int?,
    val biometricOff: Boolean,
    val waitMs: Long?,
    val onClose: () -> Unit,
    val onKey: (Char) -> Unit,
    val onBackspace: () -> Unit,
    val onSubmit: () -> Unit,
)

/** The camera cover's gate: the pages Lensly shows, which are today's unlock and unavailable screens. */
object CoverGateLook : GateLook {
    @Composable
    override fun Unlock(state: UnlockUi) {
        UnlockScreen(
            length = state.length,
            busy = state.busy,
            warning = if (state.biometricOff) BIOMETRIC_OFF_NOTICE else state.triesLeft?.let(::eraseWarning),
            onKey = state.onKey,
            onBackspace = state.onBackspace,
            onSubmit = state.onSubmit,
        )
    }

    @Composable
    override fun Unavailable(detail: String?, onClose: () -> Unit) {
        UnavailableScreen(onClose = onClose, detail = detail)
    }
}

/**
 * The hidden area: a nested graph that opens on the gate. The gate sends the owner to [home], or to the screen that fits the
 * vault's state. [warmUp] runs when the owner submits the App Password, so an app can load what [home] shows while the key is derived.
 * [newVault] is where the gate sends an owner with no vault yet. It is the App Password screen unless the app shows screens first, as
 * Lumio's first run does, and then that app's own App Password screen creates the vault with [createVault]. [biometric] is the app's
 * biometric unlock, which the unlock screen opens only when Done is pressed with nothing typed. [gateLook] draws the gate's pages, and
 * [appName] is the app's name in the copy of a build that refuses to create a vault. [content] adds the app's own screens to the graph.
 */
fun NavGraphBuilder.hiddenArea(
    navController: NavHostController,
    holder: VaultHolder,
    home: String,
    appName: String,
    warmUp: (Context) -> Unit = {},
    newVault: String = GateRoutes.CREATE_PASSWORD,
    biometric: BiometricUnlock? = null,
    gateLook: GateLook = CoverGateLook,
    content: NavGraphBuilder.() -> Unit,
) {
    navigation(startDestination = GateRoutes.GATE, route = LenslyRoute.HIDDEN) {
        composable(GateRoutes.GATE) { HiddenBackdrop { GateRoute(navController, holder, home, newVault) } }
        composable(GateRoutes.CREATE_PASSWORD) { HiddenBackdrop { CreatePasswordRoute(navController, holder, home) } }
        composable(GateRoutes.UNLOCK) { HiddenBackdrop { UnlockRoute(navController, holder, home, warmUp, biometric, gateLook) } }
        composable(GateRoutes.UNAVAILABLE) {
            HiddenBackdrop {
                gateLook.Unavailable(detail = holder.vault.refusal(appName), onClose = { leaveHidden(navController, holder) })
            }
        }
        composable(GateRoutes.DAMAGED) {
            HiddenBackdrop {
                gateLook.Unavailable(detail = VAULT_DAMAGED, onClose = { leaveHidden(navController, holder) })
            }
        }
        composable(GateRoutes.NO_SCREEN_LOCK) {
            HiddenBackdrop {
                gateLook.Unavailable(detail = NO_SCREEN_LOCK_DETAIL, onClose = { leaveHidden(navController, holder) })
            }
        }
        composable(GateRoutes.KEY_INVALIDATED) {
            HiddenBackdrop {
                gateLook.Unavailable(detail = KEY_INVALIDATED_DETAIL, onClose = { leaveHidden(navController, holder) })
            }
        }
        content()
    }
}

/**
 * Paints an opaque black backdrop behind a hidden screen. The camera stays composed underneath the hidden graph,
 * so without this its viewfinder or permission prompt would show through.
 */
@Composable
fun HiddenBackdrop(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        content()
    }
}

/**
 * Locks the vault and returns to the camera. Popping the hidden graph also drops whatever the app keeps for the visit, so no
 * secret outlives it.
 */
fun leaveHidden(navController: NavHostController, holder: VaultHolder) {
    holder.lock()
    navController.navigate(LenslyRoute.CAMERA) {
        popUpTo(LenslyRoute.CAMERA) { inclusive = false }
        launchSingleTop = true
    }
}

/**
 * The launches that the app makes itself. The system document picker runs in its own activity, so this app stops while it is
 * open. [arm] is called before such a launch, and the next stop it causes does not lock the vault, or the import could never
 * finish. [takeStop] is consulted on every stop and disarms at once, so any later stop locks as usual. [disarm] runs when the
 * launch returns, in case its stop was never seen.
 *
 * A launch counts as open from [arm] until [disarm], and a screen that opens the camera, such as a scanner, counts from [hold]
 * until [release]. An open launch keeps a policy's idle countdown waiting, see [LockRules]. The return of a launch is recorded as
 * [lastReturnMs], which counts as activity. The count is observable, so a composable that reads [openCount] restarts with it.
 */
object PickerStop {
    private var armed = false
    private var returnedAt = 0L
    private val openLaunches = mutableIntStateOf(0)

    /** How many launches are open. Read in composition, so a change restarts the idle countdown. */
    val openCount: Int get() = openLaunches.intValue

    /** The clock time of the last return of a launch, or 0 before the first. */
    val lastReturnMs: Long get() = returnedAt

    /** Called before a launch that stops the app. The next stop it causes does not lock the vault. */
    @Synchronized
    fun arm() {
        armed = true
        openLaunches.intValue++
    }

    /** Called when the launch returns. A return counts as activity, at [now]. */
    @Synchronized
    fun disarm(now: Long = SystemClock.elapsedRealtime()) {
        armed = false
        release(now)
    }

    /** Called when a screen that opens the camera appears. Its stop, if any, still locks: only the idle countdown waits. */
    @Synchronized
    fun hold() {
        openLaunches.intValue++
    }

    /** Called when that screen goes. The return counts as activity, at [now]. */
    @Synchronized
    fun release(now: Long = SystemClock.elapsedRealtime()) {
        if (openLaunches.intValue > 0) {
            openLaunches.intValue--
            returnedAt = now
        }
    }

    /** True once after [arm], for the stop that the launch causes. */
    @Synchronized
    fun takeStop(): Boolean = armed.also { armed = false }

    /** Clears every launch. Tests use it between cases. */
    @Synchronized
    fun reset() {
        armed = false
        returnedAt = 0L
        openLaunches.intValue = 0
    }
}

/** Moves within the hidden graph. The gate and password screens behind the new screen are removed. */
fun NavHostController.openHidden(route: String) {
    navigate(route) {
        popUpTo(LenslyRoute.HIDDEN) { inclusive = false }
    }
}

/**
 * The outcome of creating or unlocking the vault from a screen. [Cancelled] is a device check the owner did not pass; nothing
 * is opened or changed, and the owner can try again.
 */
enum class GateOutcome { Opened, Rejected, Erased, Unavailable, Cancelled, KeyInvalidated }

@Composable
private fun GateRoute(navController: NavHostController, holder: VaultHolder, home: String, newVault: String) {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        // An open session goes straight to home, without a Dispatchers.IO hop. A wipe marker cannot coexist with an open session:
        // every wipe locks the holder first, and the gate never unlocks while the marker exists (see VaultLayout.exists). So nothing
        // is left to finish, and the route does not wait on a background round trip.
        val next = if (holder.openSession() != null) {
            home
        } else {
            // A reset that stopped half way is finished before the vault is looked at. If it fails again, the vault stays hidden (see isPartial).
            withContext(Dispatchers.IO) { holder.vault.finishPendingWipe() }
            when {
                holder.vault.exists() -> GateRoutes.UNLOCK
                withContext(Dispatchers.IO) { holder.vault.isPartial() } -> GateRoutes.DAMAGED
                // A device that may not hold a vault is told so before the owner types a password for nothing.
                withContext(Dispatchers.IO) { holder.vault.canCreate() } ->
                    if (VaultKeystore.isDeviceSecure(context)) newVault else GateRoutes.NO_SCREEN_LOCK
                else -> GateRoutes.UNAVAILABLE
            }
        }
        // The IO hops above may resume this coroutine off Main, and navigation must run on Main. Immediate runs inline on Main, so an
        // open session still navigates within the first pass.
        withContext(Dispatchers.Main.immediate) {
            navController.navigate(next) {
                popUpTo(GateRoutes.GATE) { inclusive = true }
            }
        }
    }
}

@Composable
private fun CreatePasswordRoute(navController: NavHostController, holder: VaultHolder, home: String) {
    val context = LocalContext.current.applicationContext
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
                        val outcome = createVault(holder, expected, context)
                        busy = false
                        when (outcome) {
                            GateOutcome.Opened -> navController.openHidden(home)
                            // Nothing was created, so the owner types the password again.
                            GateOutcome.Cancelled -> state = CreatePasswordState(step = CreateStep.Enter)
                            else -> navController.openHidden(GateRoutes.UNAVAILABLE)
                        }
                    }
                }
            }
        },
    )
}

/**
 * The Failure Delay still to run, counted down once a second from [remainingMs], which is read again whenever it changes. It is null once the
 * wait is over, and while none runs. The count uses the monotonic clock, so a moved wall clock does not change it.
 */
@Composable
private fun rememberWaitLeft(remainingMs: Long?): State<Long?> = produceState(remainingMs, remainingMs) {
    if (remainingMs == null) return@produceState
    val end = SystemClock.elapsedRealtime() + remainingMs
    while (true) {
        val left = end - SystemClock.elapsedRealtime()
        if (left <= 0L) {
            value = null
            return@produceState
        }
        value = left
        delay(min(left, COUNT_STEP_MS))
    }
}

private const val COUNT_STEP_MS = 1_000L

@Composable
private fun UnlockRoute(
    navController: NavHostController,
    holder: VaultHolder,
    home: String,
    warmUp: (Context) -> Unit,
    biometric: BiometricUnlock?,
    gateLook: GateLook,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val buffer = remember { PasswordBuffer() }
    var length by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    var triesLeft by remember { mutableStateOf<Int?>(null) }
    var waitMs by remember { mutableStateOf<Long?>(null) }
    val waitLeft by rememberWaitLeft(waitMs)
    var biometricOff by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }

    BackHandler { leaveHidden(navController, holder) }
    DisposableEffect(Unit) {
        onDispose { buffer.clear() }
    }
    LaunchedEffect(Unit) {
        triesLeft = holder.vault.triesBeforeErase()
        waitMs = holder.vault.waitRemainingMs()
    }
    // The biometric prompt opens only when the owner presses Done with nothing typed. The page never opens it by itself, and looks the same
    // with biometric unlock on or off, so someone who forces the owner to unlock cannot see that it is on (or that a second vault exists).
    // A cancel leaves the password; a failure turns biometric unlock off.
    fun askBiometric() {
        if (biometric == null || asking || !biometric.isOn()) return
        asking = true
        scope.launch {
            try {
                launch(Dispatchers.IO) { runCatching { warmUp(context) } }
                val epoch = holder.unlockEpoch()
                when (val outcome = biometric.unlock(holder.vault)) {
                    is BiometricOutcome.Unlocked -> {
                        val kept = withContext(Dispatchers.IO + NonCancellable) { holder.adopt(outcome.session, epoch) }
                        if (kept) navController.openHidden(home)
                    }
                    BiometricOutcome.Declined -> {}
                    BiometricOutcome.TurnedOff -> biometricOff = true
                }
            } finally {
                asking = false
            }
        }
    }

    gateLook.Unlock(
        UnlockUi(
            length = length,
            busy = busy,
            refused = refused,
            triesLeft = triesLeft,
            biometricOff = biometricOff,
            waitMs = waitLeft,
            onClose = { leaveHidden(navController, holder) },
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
                if (busy) return@submit
                if (buffer.isEmpty()) {
                    askBiometric()
                    return@submit
                }
                val password = buffer.toCharArray()
                buffer.clear()
                length = 0
                biometricOff = false
                refused = false
                busy = true
                // The app's warm-up runs while the key is derived, so what Home shows is ready when it opens.
                scope.launch(Dispatchers.IO) { runCatching { warmUp(context) } }
                scope.launch {
                    val outcome = unlockWith(holder, password, context)
                    busy = false
                    when (outcome) {
                        GateOutcome.Opened -> navController.openHidden(home)
                        // A wrong password clears the field. Near the erase, the warning is read again, since this try may have counted. The wait
                        // is read again too, so a refused try shows the countdown the next try faces.
                        GateOutcome.Rejected -> {
                            refused = true
                            triesLeft = holder.vault.triesBeforeErase()
                            waitMs = holder.vault.waitRemainingMs()
                        }
                        // A cancelled check counted no try, and the field was cleared on submit, so nothing is read again.
                        GateOutcome.Cancelled -> Unit
                        // The last allowed try erased the vault, so the owner leaves as after any wipe.
                        GateOutcome.Erased -> leaveHidden(navController, holder)
                        GateOutcome.KeyInvalidated -> navController.openHidden(GateRoutes.KEY_INVALIDATED)
                        GateOutcome.Unavailable -> navController.openHidden(GateRoutes.UNAVAILABLE)
                    }
                }
            },
        ),
    )
}

/**
 * Creates the vault with [password], then opens it with the same password. [app.lensly.core.vault.Vault.create]
 * zeroes the array it is given, so it gets a copy. The original is zeroed here, on every path.
 */
suspend fun createVault(holder: VaultHolder, password: CharArray, context: Context): GateOutcome = try {
    if (holder.vault.create(password.copyOf()) != CreateResult.Created) {
        GateOutcome.Unavailable
    } else {
        when (val outcome = unlockWith(holder, password, context)) {
            GateOutcome.Rejected, GateOutcome.Cancelled -> GateOutcome.Unavailable
            else -> outcome
        }
    }
} catch (e: VaultAuthRequiredException) {
    GateOutcome.Cancelled
} catch (e: VaultException) {
    GateOutcome.Unavailable
} finally {
    password.fill('\u0000')
}

/**
 * Unlocks with [password], which the vault zeroes, and keeps the session if the vault was not locked meanwhile.
 * A damaged vault gives [GateOutcome.Unavailable]. A deleted vault key gives [GateOutcome.KeyInvalidated]: either the Keystore says so,
 * or it fails for the vault key on a device with no screen lock left, which is what removing the lock does to the key.
 */
private suspend fun unlockWith(holder: VaultHolder, password: CharArray, context: Context): GateOutcome {
    val epoch = holder.unlockEpoch()
    return try {
        when (val result = holder.vault.unlock(password)) {
            is UnlockResult.Unlocked -> {
                // Adopting attaches the session, which removes the wallets a crash left unfinished: file and Keystore work, so it runs off
                // Main. It runs under NonCancellable, so an opened session is always adopted, or closed if the vault was locked meanwhile.
                val kept = withContext(Dispatchers.IO + NonCancellable) { holder.adopt(result.session, epoch) }
                if (kept) GateOutcome.Opened else GateOutcome.Rejected
            }
            UnlockResult.Rejected -> GateOutcome.Rejected
            UnlockResult.Erased -> GateOutcome.Erased
        }
    } catch (e: VaultAuthRequiredException) {
        GateOutcome.Cancelled
    } catch (e: VaultKeyInvalidatedException) {
        GateOutcome.KeyInvalidated
    } catch (e: VaultException) {
        if (VaultKeystore.isDeviceSecure(context)) GateOutcome.Unavailable else GateOutcome.KeyInvalidated
    }
}

/** The gate's message for a password the policy refuses. */
fun PolicyReason.toPolicyError(): PolicyError = when (this) {
    PolicyReason.TOO_SHORT -> PolicyError.TooShort
    PolicyReason.WEAK_SCORE, PolicyReason.LOW_ENTROPY -> PolicyError.TooWeak
}
