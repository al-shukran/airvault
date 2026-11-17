package app.lensly.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.lensly.app.VaultHolder
import app.lensly.app.entryGestureHolder
import app.lensly.app.vaultHolder
import app.lensly.ui.camera.CameraScreen
import app.lensly.ui.components.LocalVaultLock
import kotlinx.coroutines.delay

/** The routes of the camera cover and of the Hidden Area, which both apps share. */
object LenslyRoute {
    const val CAMERA = "camera"
    const val GALLERY = "gallery"
    const val HIDDEN = "hidden"
}

/** Idle time in the hidden area after which Lensly's vault locks. It is the timeout of [LockPolicy.LENSLY]. */
const val INACTIVITY_LOCK_MS = 2 * 60 * 1000L

/**
 * The camera cover and the navigation around it, which Lensly and Lumio share. It opens on the camera. The hidden area is entered by the
 * viewfinder gesture, and it is the only place the vault stays unlocked. The gallery checks its password without keeping the vault open.
 * Pressing LOCK, or leaving the hidden area, locks the vault and returns to the camera. [lockPolicy] says what else locks it: leaving
 * the foreground, and the idle timeout. [hidden] adds the app's own hidden area, which starts with [hiddenArea]. [lockVault] is what the
 * top bar's lock icon does, and [leaveHidden] by default.
 *
 * [debugHiddenRequest] counts debug launches that asked for the hidden area. It is always zero in release builds.
 */
@Composable
fun CoverNavHost(
    debugHiddenRequest: Int = 0,
    modifier: Modifier = Modifier,
    lockVault: (NavHostController, VaultHolder) -> Unit = ::leaveHidden,
    lockPolicy: LockPolicy = LockPolicy.LENSLY,
    hidden: NavGraphBuilder.(NavHostController, VaultHolder) -> Unit,
) {
    val holder = LocalContext.current.vaultHolder()
    val entryGestures = LocalContext.current.entryGestureHolder()
    val navController = rememberNavController()
    ReturnToCameraWhenBackgrounded(navController, holder, lockPolicy)
    OpenHiddenOnDebugLaunch(navController, debugHiddenRequest)
    val touchModifier = InactivityLock(navController, holder, lockPolicy)

    Box(modifier = modifier.fillMaxSize().then(touchModifier)) {
        CompositionLocalProvider(LocalVaultLock provides { lockVault(navController, holder) }) {
            NavHost(
                navController = navController,
                startDestination = LenslyRoute.CAMERA,
                modifier = Modifier.fillMaxSize(),
                enterTransition = LenslyNavTransitions.enter,
                exitTransition = LenslyNavTransitions.exit,
                popEnterTransition = LenslyNavTransitions.popEnter,
                popExitTransition = LenslyNavTransitions.popExit,
            ) {
                composable(LenslyRoute.CAMERA) {
                    CameraScreen(
                        onOpenGallery = { navController.navigate(LenslyRoute.GALLERY) { launchSingleTop = true } },
                        onHiddenEntry = { navController.navigate(LenslyRoute.HIDDEN) { launchSingleTop = true } },
                        entryGesture = entryGestures.current,
                    )
                }
                composable(LenslyRoute.GALLERY) {
                    // The gallery opens from the camera while the vault is locked, so it shows no lock icon.
                    // Its gate asks for a vault password before any photo is shown.
                    CompositionLocalProvider(LocalVaultLock provides null) {
                        GalleryGate(vault = holder.vault, onBack = { navController.popBackStack() })
                    }
                }
                hidden(navController, holder)
            }
        }
    }
}

/**
 * Locks the vault once [LockPolicy.idleTimeoutMs] pass without a touch while the hidden area is showing. The countdown starts when the
 * hidden area opens, restarts with each touch, and keeps running in the background. It waits while an own launch is open, when the
 * policy keeps those unlocked. Returns a modifier that records every touch. [clock] reads the time in milliseconds: the app uses the
 * elapsed real time, and a test supplies its own clock.
 */
@Composable
fun InactivityLock(
    navController: NavHostController,
    holder: VaultHolder,
    policy: LockPolicy = LockPolicy.LENSLY,
    clock: () -> Long = { SystemClock.elapsedRealtime() },
): Modifier {
    val lifecycleOwner = LocalLifecycleOwner.current
    val entry by navController.currentBackStackEntryAsState()
    val inHidden = entry?.destination.inHiddenGraph()
    val enteredAt = remember(inHidden) { clock() }
    var lastTouch by remember { mutableLongStateOf(0L) }
    // Coming back to the foreground checks the countdown again: a process that was frozen or asleep has missed its timer.
    var resumes by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) resumes++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val launchOpen = PickerStop.openCount > 0
    LaunchedEffect(inHidden, lastTouch, resumes, policy, launchOpen) {
        if (!inHidden || LockRules.idlePaused(policy, launchOpen)) return@LaunchedEffect
        val remaining = LockRules.idleRemainingMs(policy, enteredAt, lastTouch, PickerStop.lastReturnMs, clock())
        if (remaining > 0) delay(remaining)
        leaveHidden(navController, holder)
    }
    return Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            lastTouch = clock()
        }
    }
}

/** A debug launch that asks for the hidden area opens it, unless it is already open. Each request acts once. */
@Composable
private fun OpenHiddenOnDebugLaunch(navController: NavHostController, request: Int) {
    LaunchedEffect(request) {
        if (request == 0) return@LaunchedEffect
        val inHidden = navController.currentBackStackEntry?.destination.inHiddenGraph()
        if (!inHidden) navController.navigate(LenslyRoute.HIDDEN) { launchSingleTop = true }
    }
}

/**
 * When the policy locks on a stop ([LockPolicy.lockWhenBackgrounded]), leaving the foreground locks the vault and the back stack returns
 * to the camera, so the app reopens on the camera. Configuration changes such as rotation also stop the activity, so they are excluded,
 * and a stop that an own launch causes never locks.
 */
@Composable
fun ReturnToCameraWhenBackgrounded(navController: NavHostController, holder: VaultHolder, policy: LockPolicy = LockPolicy.LENSLY) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val current by rememberUpdatedState(policy)
    DisposableEffect(lifecycleOwner, navController) {
        val observer = LifecycleEventObserver { _, event ->
            val rotating = context.findActivity()?.isChangingConfigurations == true
            if (event == Lifecycle.Event.ON_STOP && !rotating) {
                // The document picker and the other launches of the app stop it on purpose. Their stop does not lock the vault.
                val causedByLaunch = PickerStop.takeStop()
                if (LockRules.lockOnStop(current, causedByOwnLaunch = causedByLaunch)) leaveHidden(navController, holder)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** True when [this] is the hidden graph itself or one of its screens. Walks up the parent chain. */
private fun NavDestination?.inHiddenGraph(): Boolean {
    var current: NavDestination? = this
    while (current != null) {
        if (current.route == LenslyRoute.HIDDEN) return true
        current = current.parent
    }
    return false
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
