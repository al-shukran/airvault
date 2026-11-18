package app.lensly.ui.settings

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
import app.lensly.app.EntryGestureHolder
import app.lensly.app.VaultHolder
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.flow.EntryGestureChangeFlow
import app.lensly.ui.flow.EntryGestureChangePorts
import app.lensly.ui.flow.EntryGestureOutcome
import app.lensly.ui.flow.EntryGestureStep
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.wallet.PasswordStep
import kotlinx.coroutines.launch

/** A change of the gesture has two steps: the App Password, then the practice. */
private const val GESTURE_STEP_COUNT = 2

/**
 * The change to [target]: the App Password, then the practice of [target]. Only a practice that works saves it, and the saved gesture
 * then shows in the choice. A wrong password says nothing, as at the gate. [onSaved] runs once the gesture is saved, and [onUnavailable]
 * when the vault cannot be checked. The app keeps its own navigation: it decides where [onBack] and the two outcomes go.
 */
@Composable
fun EntryGestureChangeScreen(
    holder: VaultHolder,
    gestures: EntryGestureHolder,
    target: EntryGesture,
    onSaved: () -> Unit,
    onUnavailable: () -> Unit,
    onBack: () -> Unit,
) {
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
            EntryGestureOutcome.SAVED -> onSaved()
            EntryGestureOutcome.UNAVAILABLE -> onUnavailable()
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
            onBack = onBack,
            modifier = Modifier,
            title = "Hidden Entry Gesture",
            prompt = "Enter your Application Password to change the gesture.",
            stepCount = GESTURE_STEP_COUNT,
        )
        is EntryGestureStep.Practice -> EntryGesturePracticeStep(
            gesture = target,
            onPerformed = { scope.launch { flow.practiced() } },
            onBack = onBack,
        )
        is EntryGestureStep.Ended -> Unit
    }
}
