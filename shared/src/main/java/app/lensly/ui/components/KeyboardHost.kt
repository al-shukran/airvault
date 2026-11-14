package app.lensly.ui.components

import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.awaitCancellation

private const val KEYBOARD_MOTION_MS = 220

/**
 * Binds the in-app keyboard to field focus. [focused] is true while a field takes the keys. Then a tap on the screen that no control
 * takes, and the system Back, call [onUnfocus], which hides the keyboard. Back is taken here only while the keyboard is up, so the next
 * Back leaves the screen as it did before.
 *
 * The Back handler is added once the composition has settled, not in the same pass as the route's own handlers. Those can otherwise be
 * tried ahead of it, and the screen's Back would then leave the screen without hiding the keyboard first.
 */
@Composable
fun KeyboardHost(
    focused: Boolean,
    onUnfocus: () -> Unit,
    content: @Composable () -> Unit,
) {
    val unfocus by rememberUpdatedState(onUnfocus)
    val dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current) { "KeyboardHost needs a Back dispatcher" }
        .onBackPressedDispatcher
    val back = remember {
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                unfocus()
            }
        }
    }
    SideEffect { back.isEnabled = focused }
    LaunchedEffect(dispatcher) {
        dispatcher.addCallback(back)
        try {
            awaitCancellation()
        } finally {
            back.remove()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(focused) {
                if (focused) detectTapGestures { unfocus() }
            },
    ) {
        content()
    }
}

/**
 * A slot at the foot of a screen. Its content slides up from the bottom edge with a fade while [visible], and slides back down when it
 * is not. The in-app keyboard sits in one. A screen can also put an action in one that takes the keyboard's place while it is hidden.
 */
@Composable
fun ColumnScope.BottomSlot(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(KEYBOARD_MOTION_MS)) { it } + fadeIn(tween(KEYBOARD_MOTION_MS)),
        exit = slideOutVertically(tween(KEYBOARD_MOTION_MS)) { it } + fadeOut(tween(KEYBOARD_MOTION_MS)),
    ) {
        content()
    }
}
