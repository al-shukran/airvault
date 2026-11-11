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
import androidx.compose.ui.Modifier
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
import app.lensly.ui.flow.EntryGestureChangeFlow
import app.lensly.ui.flow.EntryGestureChangePorts
import app.lensly.ui.flow.EntryGestureOutcome
import app.lensly.ui.flow.EntryGestureStep
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.settings.EntryGesturePracticeStep
import app.lensly.ui.settings.EntryGestureScreen
import app.lensly.ui.wallet.PasswordStep
import kotlinx.coroutines.launch

/** A change of the gesture has two steps: the App Password, then the practice. */
private const val GESTURE_STEP_COUNT = 2

/** The Hidden Entry Gesture in the hidden graph: the choice of a gesture, and the change to the one chosen. */
internal fun NavGraphBuilder.entryGestureRoutes(navController: NavHostController, holder: VaultHolder) {
    composable(HiddenRoute.ENTRY_GESTURE) { HiddenBackdrop { EntryGestureRoute(navController, holder) } }
    composable(
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
        onLock = { leaveHidden(navController, holder) },
    )
}

/**
 * The change to [target]: the App Password, then the practice of [target]. Only a practice that works saves it, and the
 * saved gesture then shows in the list. A wrong password says nothing, as at the gate. An unavailable vault shows the
 * neutral screen.
 */
@Composable
private fun EntryGestureChangeRoute(navController: NavHostController, holder: VaultHolder, target: EntryGesture) {
    rememberOpenSession(navController, holder) ?: return
    val gestures = LocalContext.current.entryGestureHolder()
    val scope = rememberCoroutineScope()
    val flow = remember(target) { EntryGestureChangeFlow(target, EntryGestureChangePorts(holder, gestures)) }
    val password = remember { PasswordBuffer() }
    val step by flow.state.collectAsState()
    var length by remember { mutableIntStateOf(0) }
    val awaitingPassword = (step as? EntryGestureStep.Password)?.busy == false

    DisposableEffect(flow) {
        onDispose { password.clear() }
    }
    LaunchedEffect(step) {
        when ((step as? EntryGestureStep.Ended)?.outcome) {
            EntryGestureOutcome.SAVED -> navController.popBackStack(HiddenRoute.ENTRY_GESTURE, inclusive = false)
            EntryGestureOutcome.UNAVAILABLE -> navController.openHidden(HiddenRoute.UNAVAILABLE)
            null -> Unit
        }
    }

    when (val current = step) {
        is EntryGestureStep.Password -> PasswordStep(
            busy = current.busy,
            length = length,
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
            onBack = { navController.popBackStack() },
            onLock = { leaveHidden(navController, holder) },
            modifier = Modifier,
            title = "Hidden entry gesture",
            prompt = "Enter your App Password to change the gesture.",
            stepCount = GESTURE_STEP_COUNT,
        )
        is EntryGestureStep.Practice -> EntryGesturePracticeStep(
            gesture = target,
            onPerformed = { scope.launch { flow.practiced() } },
            onBack = { navController.popBackStack() },
            onLock = { leaveHidden(navController, holder) },
        )
        is EntryGestureStep.Ended -> Unit
    }
}
