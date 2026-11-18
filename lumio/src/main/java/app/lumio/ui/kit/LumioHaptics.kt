package app.lumio.ui.kit

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Lumen's haptics (section 3.5): a tick for each part of a scan, a select for a choice, a copy, a success and a failure. They never fire on a scroll
 * or on a recomposition, and Lumio's minimum Android version has all of them.
 */
class LumioHaptics internal constructor(private val view: View) {
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)
    fun select() = perform(HapticFeedbackConstants.KEYBOARD_TAP)
    fun copy() = perform(HapticFeedbackConstants.LONG_PRESS)
    fun success() = perform(HapticFeedbackConstants.CONFIRM)
    fun failure() = perform(HapticFeedbackConstants.REJECT)

    private fun perform(constant: Int) {
        view.performHapticFeedback(constant)
    }
}

/** The haptics of the view that the screen is drawn in. */
@Composable
fun rememberLumioHaptics(): LumioHaptics {
    val view = LocalView.current
    return remember(view) { LumioHaptics(view) }
}
