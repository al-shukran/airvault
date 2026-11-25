package app.lensly.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.LocalReducedMotion

/**
 * The class of a screen's transition. Every class is a plain push ([LenslyNavTransitions]), except [RISE], which is a plain rise from the bottom.
 * [ROOT] keeps the NavHost's own transition, which is the same push.
 */
enum class NavMotion { ROOT, PUSH, RISE, FADE, COVER }

/**
 * Reduced motion for the NavHost's transitions. Transition lambdas are built outside composition, so they cannot read
 * [LocalReducedMotion]; [MirrorNavMotion] copies the local here, and each transition reads it when it starts.
 */
object NavReducedMotion {
    var reduced = false
}

/** Copies the Lumen reduced-motion setting into [NavReducedMotion]. It sits inside the theme, beside the NavHost. */
@Composable
fun MirrorNavMotion() {
    val reduced = LocalReducedMotion.current
    SideEffect { NavReducedMotion.reduced = reduced }
}

/** A transition under reduced motion is a cut: [cut] is returned and [motion] is never built. */
fun <T> cutWhenReduced(reduced: Boolean, cut: T, motion: () -> T): T = if (reduced) cut else motion()

private val rise = tween<IntOffset>(ClearAirMotion.PUSH_MS, easing = ClearAirMotion.pushEasing)

/** How a screen of class [motion] comes in when it is pushed. The screen beneath stays still, so it needs no exit. */
fun AnimatedContentTransitionScope<NavBackStackEntry>.enterOf(motion: NavMotion): EnterTransition = when (motion) {
    NavMotion.RISE -> slideInVertically(rise) { it }
    else -> LenslyNavTransitions.enter(this)
}

/** How a screen of class [motion] goes out when it is popped. The screen beneath was in place all along, so it needs no enter. */
fun AnimatedContentTransitionScope<NavBackStackEntry>.popExitOf(motion: NavMotion): ExitTransition = when (motion) {
    NavMotion.RISE -> slideOutVertically(rise) { it }
    else -> LenslyNavTransitions.popExit(this)
}
