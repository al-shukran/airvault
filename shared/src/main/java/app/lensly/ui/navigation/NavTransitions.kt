package app.lensly.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavBackStackEntry

/** How long a screen takes to enter or leave. It is short on purpose, so the app feels immediate. */
private const val TRANSITION_MS = 220

/** A screen slides in from one twelfth of the width, so the motion stays small. */
private const val SLIDE_DIVISOR = 12

/**
 * Screen transitions for the nav host: a short fade with a small horizontal slide when moving forward, and the reverse
 * when going back.
 */
object LenslyNavTransitions {
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(tween(TRANSITION_MS)) + slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec = tween(TRANSITION_MS),
            initialOffset = { it / SLIDE_DIVISOR },
        )
    }

    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(tween(TRANSITION_MS))
    }

    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(tween(TRANSITION_MS))
    }

    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(tween(TRANSITION_MS)) + slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.End,
            animationSpec = tween(TRANSITION_MS),
            targetOffset = { it / SLIDE_DIVISOR },
        )
    }
}
