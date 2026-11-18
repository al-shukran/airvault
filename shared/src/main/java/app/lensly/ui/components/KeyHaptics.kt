package app.lensly.ui.components

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView

/**
 * Plays one haptic for every key press. The haptic is the same for every key, so the buzz cannot reveal which key was pressed. There is
 * no sound. It goes through the view's keyboard-tap feedback, so it follows the system's touch-feedback setting: with that turned off,
 * the keys stay silent.
 */
class KeyHaptics internal constructor(private val view: View) {
    fun tap() {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
}

/**
 * Returns the [KeyHaptics] of the window's view. It is made once per view and reused for every key after that.
 */
@Composable
fun rememberKeyHaptics(): KeyHaptics {
    val view = LocalView.current
    return remember(view) { KeyHaptics(view) }
}

/**
 * Calls [KeyHaptics.tap] when a pointer goes down on this element. It runs in the initial pass and
 * does not consume the event, so the element's own click handling is unchanged. Pass
 * `enabled = false` for a disabled key, which stays silent.
 */
fun Modifier.tapHaptic(haptics: KeyHaptics, enabled: Boolean = true): Modifier =
    pointerInput(haptics, enabled) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (enabled && event.type == PointerEventType.Press) haptics.tap()
            }
        }
    }
