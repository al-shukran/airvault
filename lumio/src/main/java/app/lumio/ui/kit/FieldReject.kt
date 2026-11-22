package app.lumio.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val REJECT_MS = 240
private const val REJECT_DP = 4f

/**
 * The reject of a form field (K5): the moment [error] appears, the field shakes 4 dp for 240 ms and the REJECT haptic fires. Only that moment does
 * it: a message that changes while it is shown, or a field that opens already wrong, stays still. The returned modifier moves the field.
 */
@Composable
fun rejectOf(error: String?): Modifier {
    val haptics = rememberLumioHaptics()
    val shift = remember { Animatable(0f) }
    val failed = error != null
    var wasFailed by remember { mutableStateOf(failed) }
    LaunchedEffect(failed) {
        if (!failed) {
            shift.snapTo(0f)
        } else if (!wasFailed) {
            haptics.failure()
            shift.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = REJECT_MS
                    0f at 0
                    -REJECT_DP at 40
                    REJECT_DP at 80
                    -REJECT_DP at 120
                    REJECT_DP at 160
                    -REJECT_DP at 200
                    0f at REJECT_MS
                },
            )
        }
        wasFailed = failed
    }
    return Modifier.offset(x = shift.value.dp)
}
