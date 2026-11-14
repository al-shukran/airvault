package app.lensly.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext

private const val TAP_DURATION_MS = 35L
private const val TAP_AMPLITUDE = 255

/**
 * Plays one haptic for every key press. The effect is chosen once per device and never varies by
 * key, so the buzz cannot reveal which key was pressed. There is no sound.
 */
class KeyHaptics internal constructor(
    private val vibrator: Vibrator?,
    private val effect: VibrationEffect?,
) {
    // VIBRATE is a normal permission that the hosting app declares. This library's manifest cannot show lint that.
    @SuppressLint("MissingPermission")
    fun tap() {
        val v = vibrator ?: return
        val e = effect ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            // Usage accessibility keeps the buzz on when the "touch feedback" setting is off.
            v.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY))
        } else {
            v.vibrate(e)
        }
    }
}

/**
 * Returns the device's [KeyHaptics]. The effect is decided once, when the composable first runs,
 * and reused for every key after that.
 */
@Composable
fun rememberKeyHaptics(): KeyHaptics {
    val context = LocalContext.current.applicationContext
    return remember(context) { createKeyHaptics(context) }
}

private fun createKeyHaptics(context: Context): KeyHaptics {
    val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    if (vibrator == null || !vibrator.hasVibrator()) return KeyHaptics(null, null)
    val effect = if (vibrator.hasAmplitudeControl()) {
        VibrationEffect.createOneShot(TAP_DURATION_MS, TAP_AMPLITUDE)
    } else {
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
    }
    return KeyHaptics(vibrator, effect)
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
