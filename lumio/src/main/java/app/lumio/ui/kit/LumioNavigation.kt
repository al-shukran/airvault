package app.lumio.ui.kit

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.lensly.ui.navigation.LenslyNavTransitions
import app.lensly.ui.navigation.NavReducedMotion
import app.lensly.ui.theme.ClearAirMotion
import app.lumio.LumioRoute

/** The cross-fade between two tab roots: 120 ms, so a tab switch reads as one step without a slide. */
private const val TAB_FADE_MS = 120

/** Whether [route] is the root of a tab, one of the five places of the tab bar. */
internal fun isTabRoot(route: String?): Boolean = route in LumioRoute.TAB_ROOTS

/**
 * A Lumio screen of the NavHost, with the motion that Clear Air sets for it. A tab root cross-fades with the tab it meets. Every other screen is a
 * place: it slides in from the right on the push curve, while the screen beneath stays still, and it slides out to the right the same way, with nothing fading. Reduced
 * motion cross-fades in [ClearAirMotion.CROSS_FADE_MS] instead of moving.
 */
internal fun NavGraphBuilder.lumioComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedVisibilityScope.(NavBackStackEntry) -> Unit,
) {
    composable(
        route = route,
        arguments = arguments,
        enterTransition = {
            when {
                NavReducedMotion.reduced -> crossFadeIn()
                isTabRoot(route) -> tabFadeIn()
                else -> LenslyNavTransitions.enter(this)
            }
        },
        exitTransition = {
            when {
                NavReducedMotion.reduced -> crossFadeOut()
                isTabRoot(targetState.destination.route) -> tabFadeOut()
                // The screen beneath a push holds its place until the push lands, so nothing shows through it.
                else -> LenslyNavTransitions.exit(this)
            }
        },
        popEnterTransition = {
            when {
                NavReducedMotion.reduced -> crossFadeIn()
                isTabRoot(initialState.destination.route) -> tabFadeIn()
                // A page that a pop reveals was already in place beneath the page that leaves.
                else -> LenslyNavTransitions.popEnter(this)
            }
        },
        popExitTransition = {
            when {
                NavReducedMotion.reduced -> crossFadeOut()
                isTabRoot(route) -> tabFadeOut()
                else -> LenslyNavTransitions.popExit(this)
            }
        },
    ) { entry -> content(entry) }
}

private fun tabFadeIn(): EnterTransition = fadeIn(tween(TAB_FADE_MS))

private fun tabFadeOut(): ExitTransition = fadeOut(tween(TAB_FADE_MS))

private fun crossFadeIn(): EnterTransition = fadeIn(tween(ClearAirMotion.CROSS_FADE_MS))

private fun crossFadeOut(): ExitTransition = fadeOut(tween(ClearAirMotion.CROSS_FADE_MS))
