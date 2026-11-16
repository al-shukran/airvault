package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.lensly.app.VaultHolder
import app.lensly.app.entryGestureHolder
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lensly.ui.settings.EntryGestureChangeScreen
import app.lensly.ui.settings.EntryGestureScreen

/**
 * The Hidden Entry Gesture in Lumio's Settings: the choice of a gesture, and the change to the one chosen. Both screens are Lensly's,
 * shared through :shared, so the two apps offer the same gestures and check the App Password the same way.
 */
internal fun NavGraphBuilder.entryGestureRoutes(navController: NavHostController, holder: VaultHolder, selection: NetworkSelection) {
    composable(LumioRoute.ENTRY_GESTURE) {
        LumioBackdrop(selection) { EntryGestureChoiceRoute(navController, holder) }
    }
    composable(
        route = LumioRoute.ENTRY_GESTURE_CHANGE,
        arguments = listOf(navArgument(LumioRoute.GESTURE_ARG) { type = NavType.IntType }),
    ) { entry ->
        LumioBackdrop(selection) { EntryGestureChangeRoute(navController, holder, entry.gesture()) }
    }
}

/** The gesture the route names. A missing one reads as the default. */
private fun NavBackStackEntry.gesture(): EntryGesture =
    EntryGesture.fromStoredId(arguments?.getInt(LumioRoute.GESTURE_ARG) ?: EntryGesture.DEFAULT.storedId)

/** The four gestures. Choosing the current one does nothing, and choosing another opens its change. */
@Composable
private fun EntryGestureChoiceRoute(navController: NavHostController, holder: VaultHolder) {
    if (!rememberGateSession(navController, holder)) return
    EntryGestureScreen(
        current = LocalContext.current.entryGestureHolder().current,
        onChoose = { gesture -> navController.navigate(LumioRoute.entryGestureChange(gesture)) },
        onBack = { navController.popBackStack() },
    )
}

/**
 * The change to [target]: the App Password, then the practice of [target]. Only a practice that works saves it, and the saved gesture
 * then shows in the choice. An unavailable vault shows the neutral screen.
 */
@Composable
private fun EntryGestureChangeRoute(navController: NavHostController, holder: VaultHolder, target: EntryGesture) {
    if (!rememberGateSession(navController, holder)) return
    EntryGestureChangeScreen(
        holder = holder,
        gestures = LocalContext.current.entryGestureHolder(),
        target = target,
        onSaved = { navController.popBackStack(LumioRoute.ENTRY_GESTURE, inclusive = false) },
        onUnavailable = { navController.openHidden(GateRoutes.UNAVAILABLE) },
        onBack = { navController.popBackStack() },
    )
}

/** True while the vault is open. While it is locked, the gate takes over, as on every Lumio screen. */
@Composable
private fun rememberGateSession(navController: NavHostController, holder: VaultHolder): Boolean {
    val open = holder.openSession() != null
    LaunchedEffect(open) {
        if (!open) navController.openHidden(GateRoutes.GATE)
    }
    return open
}
