package app.lensly.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import app.lensly.ui.theme.LocalReducedMotion
import app.lensly.ui.theme.LumenMotion

/**
 * The class of a screen's transition (section 3.2 of the Phase 2.8 spec). A screen's class sets how it comes in and how it goes out; the screen it
 * meets sets the other half of a move. [ROOT] keeps the NavHost's own transition, which is Lensly's fade and slide.
 */
enum class NavMotion { ROOT, PUSH, RISE, FADE }

/**
 * Reduced motion for the NavHost's transitions (section 3.6). Transition lambdas are built outside composition, so they cannot read
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

/** A transition under reduced motion is a cut (section 3.6): [cut] is returned and [motion] is never built. */
fun <T> cutWhenReduced(reduced: Boolean, cut: T, motion: () -> T): T = if (reduced) cut else motion()

/** The distance a Rise screen scales from, and the scale a screen beneath it goes to (section 3.2). */
private const val RISE_SCALE = 0.94f

/** The spatial spring as an offset: the bounds and slides of section 3.1, with the ratio and stiffness of [LumenMotion.spatial]. */
private val spatialOffset = spring<IntOffset>(dampingRatio = LumenMotion.spatial.dampingRatio, stiffness = LumenMotion.spatial.stiffness)

/** How a screen of class [motion] comes in when it is pushed. A ROOT keeps Lensly's enter. */
fun AnimatedContentTransitionScope<NavBackStackEntry>.enterOf(motion: NavMotion): EnterTransition = when (motion) {
    NavMotion.PUSH -> pushEnter()
    NavMotion.RISE -> riseEnter()
    NavMotion.FADE -> fadeThroughEnter()
    NavMotion.ROOT -> LenslyNavTransitions.enter(this)
}

/** How a screen goes out when a screen of class [target] is pushed over it. A Rise makes the screen beneath it scale back. */
fun AnimatedContentTransitionScope<NavBackStackEntry>.exitTowards(target: NavMotion?): ExitTransition = when (target) {
    NavMotion.RISE -> riseUnderExit()
    NavMotion.FADE -> fadeOutQuick()
    else -> pushExit()
}

/** How a screen comes back when the screen of class [popped] over it is popped. The revealed screen takes the shape of the one that leaves. */
fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnterFrom(popped: NavMotion?): EnterTransition = when (popped) {
    NavMotion.RISE -> riseUnderEnter()
    NavMotion.FADE -> fadeThroughEnter()
    NavMotion.PUSH -> pushBackEnter()
    NavMotion.ROOT, null -> LenslyNavTransitions.popEnter(this)
}

/** How a screen of class [motion] goes out when it is popped. */
fun AnimatedContentTransitionScope<NavBackStackEntry>.popExitOf(motion: NavMotion): ExitTransition = when (motion) {
    NavMotion.PUSH -> pushBackExit()
    NavMotion.RISE -> riseBackExit()
    NavMotion.FADE -> fadeOutQuick()
    NavMotion.ROOT -> LenslyNavTransitions.popExit(this)
}

private fun pushEnter(): EnterTransition =
    fadeIn(tween(LumenMotion.MEDIUM, delayMillis = 40, easing = LumenMotion.EmphDecel)) +
        slideInHorizontally(spatialOffset) { it / 12 }

private fun pushExit(): ExitTransition = fadeOut(tween(90)) + slideOutHorizontally(spatialOffset) { -it / 24 }

private fun pushBackEnter(): EnterTransition =
    fadeIn(tween(LumenMotion.MEDIUM, delayMillis = 40, easing = LumenMotion.EmphDecel)) +
        slideInHorizontally(spatialOffset) { -it / 24 }

private fun pushBackExit(): ExitTransition = fadeOut(tween(90)) + slideOutHorizontally(spatialOffset) { it / 12 }

private fun riseEnter(): EnterTransition = slideInVertically(spatialOffset) { it / 8 } + fadeIn(tween(LumenMotion.MEDIUM))

private fun riseUnderExit(): ExitTransition =
    scaleOut(LumenMotion.spatial, targetScale = RISE_SCALE) + fadeOut(tween(LumenMotion.MEDIUM))

private fun riseUnderEnter(): EnterTransition =
    scaleIn(LumenMotion.spatial, initialScale = RISE_SCALE) + fadeIn(tween(LumenMotion.MEDIUM))

private fun riseBackExit(): ExitTransition = slideOutVertically(spatialOffset) { it / 8 } + fadeOut(tween(LumenMotion.MEDIUM))

/** The fade-through of the Settings root (section 3.2): the old screen fades quickly, and the new one fades in and grows from 0.96 after it. */
private fun fadeThroughEnter(): EnterTransition =
    fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.96f)

private fun fadeOutQuick(): ExitTransition = fadeOut(tween(90))
