package app.lensly.ui.navigation

import androidx.compose.runtime.Composable
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
import app.lensly.ui.settings.EntryGestureChangeScreen
import app.lensly.ui.settings.EntryGestureScreen

/** The Hidden Entry Gesture in the hidden graph: the choice of a gesture, and the change to the one chosen. */
internal fun NavGraphBuilder.entryGestureRoutes(navController: NavHostController, holder: VaultHolder) {
    vaultComposable(HiddenRoute.ENTRY_GESTURE) { HiddenBackdrop { EntryGestureRoute(navController, holder) } }
    vaultComposable(
        HiddenRoute.ENTRY_GESTURE_CHANGE,
        arguments = listOf(navArgument(HiddenRoute.GESTURE_ID_ARG) { type = NavType.IntType }),
    ) { entry ->
        HiddenBackdrop { EntryGestureChangeRoute(navController, holder, entry.gesture()) }
    }
}

/** The gesture the route names. A missing one reads as the default. */
private fun NavBackStackEntry.gesture(): EntryGesture =
    EntryGesture.fromStoredId(arguments?.getInt(HiddenRoute.GESTURE_ID_ARG) ?: EntryGesture.DEFAULT.storedId)

/** The four gestures. Choosing the current one does nothing, and choosing another opens its change. */
@Composable
private fun EntryGestureRoute(navController: NavHostController, holder: VaultHolder) {
    rememberOpenSession(navController, holder) ?: return
    val current = LocalContext.current.entryGestureHolder().current
    EntryGestureScreen(
        current = current,
        onChoose = { gesture -> navController.navigate(HiddenRoute.entryGestureChange(gesture)) },
        onBack = { navController.popBackStack() },
    )
}

/**
 * The change to [target]: the App Password, then the practice of [target]. Only a practice that works saves it, and the
 * saved gesture then shows in the list. A wrong password says nothing, as at the gate. An unavailable vault shows the
 * neutral screen. The screen itself is shared with Lumio, see [EntryGestureChangeScreen].
 */
@Composable
private fun EntryGestureChangeRoute(navController: NavHostController, holder: VaultHolder, target: EntryGesture) {
    rememberOpenSession(navController, holder) ?: return
    EntryGestureChangeScreen(
        holder = holder,
        gestures = LocalContext.current.entryGestureHolder(),
        target = target,
        onSaved = { navController.popBackStack(HiddenRoute.ENTRY_GESTURE, inclusive = false) },
        onUnavailable = { navController.openHidden(HiddenRoute.UNAVAILABLE) },
        onBack = { navController.popBackStack() },
    )
}
