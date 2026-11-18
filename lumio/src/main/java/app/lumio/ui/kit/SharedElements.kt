package app.lumio.ui.kit

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.LumenMotion

/**
 * An icon or an orb that moves from one screen to the next (section 3.3). The same [key] on both screens draws the element once, moving between
 * them, and it keeps its shape. Outside the NavHost, as in a golden, the modifier does nothing.
 */
@Composable
fun Modifier.lumioSharedElement(key: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val visible = LocalNavScope.current ?: return this
    return with(shared) {
        this@lumioSharedElement.sharedElement(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = visible,
            boundsTransform = LumenMotion.sharedBoundsTransform,
        )
    }
}

/**
 * A text that moves from one screen to the next (section 3.3). Its style differs between the two ends, so it moves as bounds and scales to fit them.
 * Outside the NavHost the modifier does nothing.
 */
@Composable
fun Modifier.lumioSharedBounds(key: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val visible = LocalNavScope.current ?: return this
    return with(shared) {
        this@lumioSharedBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = visible,
            boundsTransform = LumenMotion.sharedBoundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
        )
    }
}
