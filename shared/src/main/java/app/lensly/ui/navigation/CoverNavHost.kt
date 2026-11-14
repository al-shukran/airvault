package app.lensly.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import app.lensly.ui.camera.GalleryScreen
import kotlinx.coroutines.delay

/** The routes of the camera cover and of the Hidden Area, which both apps share. */
object LenslyRoute {
    const val CAMERA = "camera"
    const val GALLERY = "gallery"
    const val HIDDEN = "hidden"
}

/** Idle time in the hidden area after which the vault locks and the owner returns to the camera. */
const val INACTIVITY_LOCK_MS = 2 * 60 * 1000L

/**
 * The camera cover and the navigation around it, which Lensly and Lumio share. It opens on the camera. The hidden area is entered by the
 * viewfinder gesture, and it is the only place the vault is unlocked. Leaving the foreground, going idle, or pressing LOCK all lock the
 * vault and return to the camera. [hidden] adds the app's own hidden area, which starts with [hiddenArea].
 *
 * [debugHiddenRequest] counts debug launches that asked for the hidden area. It is always zero in release builds.
 */
@Composable
fun CoverNavHost(
    debugHiddenRequest: Int = 0,
    modifier: Modifier = Modifier,
    hidden: NavGraphBuilder.(NavHostController, VaultHolder) -> Unit,
) {
    val holder = LocalContext.current.vaultHolder()
    val entryGestures = LocalContext.current.entryGestureHolder()
    val navController = rememberNavController()
    ReturnToCameraWhenBackgrounded(navController, holder)
    OpenHiddenOnDebugLaunch(navController, debugHiddenRequest)
    val touchModifier = InactivityLock(navController, holder)

    Box(modifier = modifier.fillMaxSize().then(touchModifier)) {
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
                GalleryScreen(onBack = { navController.popBackStack() })
            }
            hidden(navController, holder)
        }
    }
}

/**
 * Locks the vault after [INACTIVITY_LOCK_MS] without a touch while the hidden area is showing. Returns a modifier that
 * records every touch, so the countdown restarts with each one.
 */
@Composable
fun InactivityLock(navController: NavHostController, holder: VaultHolder): Modifier {
    val entry by navController.currentBackStackEntryAsState()
    val inHidden = entry?.destination.inHiddenGraph()
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(inHidden, touches) {
        if (inHidden) {
            delay(INACTIVITY_LOCK_MS)
            leaveHidden(navController, holder)
        }
    }
    return Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            touches++
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
 * Whenever the app leaves the foreground, the vault locks and the back stack returns to the camera, so the app always
 * reopens on the camera. Configuration changes such as rotation also stop the activity, so they are excluded.
 */
@Composable
fun ReturnToCameraWhenBackgrounded(navController: NavHostController, holder: VaultHolder) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    DisposableEffect(lifecycleOwner, navController) {
        val observer = LifecycleEventObserver { _, event ->
            val rotating = context.findActivity()?.isChangingConfigurations == true
            if (event == Lifecycle.Event.ON_STOP && !rotating) {
                // The document picker stops the app once on purpose. Its stop does not lock the vault.
                if (PickerStop.takeStop()) return@LifecycleEventObserver
                leaveHidden(navController, holder)
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
