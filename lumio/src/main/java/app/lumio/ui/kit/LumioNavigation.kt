package app.lumio.ui.kit

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.lensly.ui.navigation.NavReducedMotion
import app.lensly.ui.theme.ClearAirMotion
import app.lumio.LumioRoute

/** The cross-fade between two tab roots: 120 ms, so a tab switch reads as one step without a slide. */
private const val TAB_FADE_MS = 120

/** Whether [route] is the root of a tab, one of the five places of the tab bar. */
internal fun isTabRoot(route: String?): Boolean = route in LumioRoute.TAB_ROOTS

/**
 * A Lumio screen of the NavHost, with the motion that Clear Air sets for it. A tab root cross-fades with the tab it meets. Every other screen is a
 * place: it pushes in from a twelfth of the width on the push curve, while the screen beneath stays still, and it pops out the same way. Reduced
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
                else -> pushIn()
            }
        },
        exitTransition = {
            when {
                NavReducedMotion.reduced -> crossFadeOut()
                isTabRoot(targetState.destination.route) -> tabFadeOut()
                // The screen beneath a push holds its place until the push lands, so nothing shows through it.
                else -> ExitTransition.KeepUntilTransitionsFinished
            }
        },
        popEnterTransition = {
            when {
                NavReducedMotion.reduced -> crossFadeIn()
                isTabRoot(initialState.destination.route) -> tabFadeIn()
                // A page that a pop reveals was already in place beneath the page that leaves.
                else -> EnterTransition.None
            }
        },
        popExitTransition = {
            when {
                NavReducedMotion.reduced -> crossFadeOut()
                isTabRoot(route) -> tabFadeOut()
                else -> pushOut()
            }
        },
    ) { entry -> content(entry) }
}

/** The push curve's timing for the fade of a place. */
private val pushFade = tween<Float>(durationMillis = ClearAirMotion.PUSH_MS, easing = ClearAirMotion.pushEasing)

/** The same curve for the slide's offset. */
private val pushSlide = tween<IntOffset>(durationMillis = ClearAirMotion.PUSH_MS, easing = ClearAirMotion.pushEasing)

/** A place comes in from a twelfth of the width, fading in on the push curve. */
private fun pushIn(): EnterTransition = fadeIn(pushFade) + slideInHorizontally(pushSlide) { it / 12 }

/** A place goes out to the right, a twelfth of the width, fading as it goes. */
private fun pushOut(): ExitTransition = fadeOut(pushFade) + slideOutHorizontally(pushSlide) { it / 12 }

private fun tabFadeIn(): EnterTransition = fadeIn(tween(TAB_FADE_MS))

private fun tabFadeOut(): ExitTransition = fadeOut(tween(TAB_FADE_MS))

private fun crossFadeIn(): EnterTransition = fadeIn(tween(ClearAirMotion.CROSS_FADE_MS))

private fun crossFadeOut(): ExitTransition = fadeOut(tween(ClearAirMotion.CROSS_FADE_MS))
