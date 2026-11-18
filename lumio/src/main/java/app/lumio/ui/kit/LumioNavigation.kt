package app.lumio.ui.kit

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.lensly.ui.navigation.NavMotion
import app.lensly.ui.navigation.NavReducedMotion
import app.lensly.ui.navigation.cutWhenReduced
import app.lensly.ui.navigation.enterOf
import app.lensly.ui.navigation.exitTowards
import app.lensly.ui.navigation.popEnterFrom
import app.lensly.ui.navigation.popExitOf
import app.lumio.LumioRoute

/** The shared-element scope of the NavHost. [app.lumio.LumioNavHost] provides it, and it is null outside the NavHost. */
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The visibility scope of the screen on show, which [lumioComposable] provides. A shared element animates with it. */
val LocalNavScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * The class of the route pattern [route] (section 2): Settings is the Fade, the send and pairing sheets are the Rises, and every other Lumio screen is a
 * Push. The Wallets root has no class of its own (ROOT). Null when there is no route.
 */
internal fun navMotionOf(route: String?): NavMotion? = when (route) {
    null -> null
    LumioRoute.WALLETS -> NavMotion.ROOT
    LumioRoute.SETTINGS -> NavMotion.FADE
    LumioRoute.RECEIVE, LumioRoute.SEND, LumioRoute.SOLANA_SEND, LumioRoute.ALGORAND_SEND, LumioRoute.BTC_SEND,
    LumioRoute.PAIR_SCAN, LumioRoute.PAIR_REVIEW -> NavMotion.RISE
    else -> NavMotion.PUSH
}

/**
 * A Lumio screen of the NavHost, with the transitions of its class (section 3.2). A screen's own class sets how it comes in and how it goes out.
 * The screen it meets sets the other half of the move: a Rise makes the screen beneath it scale back, and a screen that comes back takes the
 * shape of the one it leaves. The class comes from [navMotionOf], so one route has one class wherever it is reached. The content gets
 * [LocalNavScope].
 */
internal fun NavGraphBuilder.lumioComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedVisibilityScope.(NavBackStackEntry) -> Unit,
) {
    val own = navMotionOf(route) ?: NavMotion.PUSH
    composable(
        route = route,
        arguments = arguments,
        enterTransition = { cutWhenReduced(NavReducedMotion.reduced, EnterTransition.None) { enterOf(own) } },
        exitTransition = { cutWhenReduced(NavReducedMotion.reduced, ExitTransition.None) { exitTowards(navMotionOf(targetState.destination.route)) } },
        popEnterTransition = { cutWhenReduced(NavReducedMotion.reduced, EnterTransition.None) { popEnterFrom(navMotionOf(initialState.destination.route)) } },
        popExitTransition = { cutWhenReduced(NavReducedMotion.reduced, ExitTransition.None) { popExitOf(own) } },
    ) { entry ->
        val scope = this
        CompositionLocalProvider(LocalNavScope provides scope) { scope.content(entry) }
    }
}
