package app.lensly.ui.sign

import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.theme.LenslyPillShape
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The test tag of the hold-to-sign control. */
const val HOLD_TO_SIGN_TAG = "hold-to-sign"

/** How long the control must be held before it signs. */
const val HOLD_TO_SIGN_MILLIS = 1_200

/** How fast the bar runs back when the hold is let go early. */
private const val RELEASE_MILLIS = 150

/** The window flags that say another window covers this one, as a tapjacking overlay does. */
private const val OBSCURED_WINDOW_FLAGS = MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED

/**
 * The control that signs. The owner presses and holds it, and a bar fills from left to right over [HOLD_TO_SIGN_MILLIS].
 * A press that lets go before the bar is full signs nothing, and the bar runs back. When the bar is full, [onSign] runs once
 * while the finger is still down, with a Confirm haptic. A tick plays when a press starts. A press that reaches the window
 * through a covering window starts no hold, and a covering window that appears mid-press cancels the hold. While [enabled] is
 * false the control takes no press at all, and it reads as disabled.
 */
@Composable
internal fun HoldToSignButton(
    enabled: Boolean,
    onSign: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val tick = rememberKeyHaptics()
    val haptics = LocalHapticFeedback.current
    val fill = remember { Animatable(0f) }
    val currentOnSign by rememberUpdatedState(onSign)
    // A hold that is cut off by the control becoming disabled leaves no bar behind.
    LaunchedEffect(enabled) {
        if (!enabled) fill.snapTo(0f)
    }
    Box(
        modifier = modifier
            .testTag(HOLD_TO_SIGN_TAG)
            .height(56.dp)
            .clip(LenslyPillShape)
            .background(if (enabled) MaterialTheme.colorScheme.onSurface else colors.surfaceVariant)
            .semantics {
                role = Role.Button
                if (!enabled) disabled()
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                // The pointer waits are their own restricted scopes, so the animation runs outside them, as a child job.
                coroutineScope {
                    while (true) {
                        awaitPointerEventScope { awaitUnobscuredDown() }
                        tick.tap()
                        val hold = launch {
                            fill.animateTo(1f, tween(HOLD_TO_SIGN_MILLIS, easing = LinearEasing))
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            currentOnSign()
                        }
                        awaitPointerEventScope { waitForUpUnlessObscured() }
                        hold.cancel()
                        fill.animateTo(0f, tween(RELEASE_MILLIS))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The bar that runs while the control is held. It sits under the label.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(fill.value)
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            text = "Hold to sign",
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = if (enabled) MaterialTheme.colorScheme.background else colors.onSurfaceVariant,
        )
    }
}

/** True when the press reached the window through another window that covers it. */
private fun PointerEvent.isObscured(): Boolean = ((motionEvent?.flags ?: 0) and OBSCURED_WINDOW_FLAGS) != 0

/** Waits for a press that starts in an unobscured window. A press through a covering window is skipped, so it starts no hold. */
private suspend fun AwaitPointerEventScope.awaitUnobscuredDown() {
    while (true) {
        val event = awaitPointerEvent()
        if (event.type == PointerEventType.Press && !event.isObscured()) return
    }
}

/**
 * Waits for the press to end, with the rules of Foundation's waitForUpOrCancellation: every pointer up ends it, and so does a
 * press that another gesture takes or that leaves the control. An obscured event ends it too, so a covering window that
 * appears mid-press cancels the hold.
 */
private suspend fun AwaitPointerEventScope.waitForUpUnlessObscured() {
    while (true) {
        val event = awaitPointerEvent()
        if (event.isObscured()) return
        if (event.changes.all { it.changedToUp() }) return
        if (event.changes.any { it.isConsumed || it.isOutOfBounds(size, extendedTouchPadding) }) return
        // A parent, such as a scroll, can take the press on the Final pass.
        if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) return
    }
}
