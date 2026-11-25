package app.lensly.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import app.lensly.ui.theme.ClearAirMotion

private val slide = tween<IntOffset>(ClearAirMotion.PUSH_MS, easing = ClearAirMotion.pushEasing)

/**
 * The one plain push for both apps. A screen slides in from the right over the screen beneath, which stays still. A pop slides it out to the right
 * and shows the screen beneath, which was in place all along. Nothing fades or scales, so a pop is the exact mirror of a push, and the slide is
 * an offset that a predictive back gesture can seek.
 */
object LenslyNavTransitions {
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, slide)
    }

    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        ExitTransition.KeepUntilTransitionsFinished
    }

    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        EnterTransition.None
    }

    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, slide)
    }
}
