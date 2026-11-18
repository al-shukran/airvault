package app.lumio.ui.kit

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntOffset
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
 * The class of the route pattern [route] (section 2): Settings is the Cover, the send and pairing sheets are the Rises, and every other Lumio screen is a
 * Push. The wallet pages drill in instead (see [isDrill]). The Wallets root has no class of its own (ROOT). Null when there is no route.
 */
internal fun navMotionOf(route: String?): NavMotion? = when (route) {
    null -> null
    LumioRoute.WALLETS -> NavMotion.ROOT
    LumioRoute.SETTINGS -> NavMotion.COVER
    LumioRoute.RECEIVE, LumioRoute.SEND, LumioRoute.SOLANA_SEND, LumioRoute.ALGORAND_SEND, LumioRoute.BTC_SEND,
    LumioRoute.PAIR_SCAN, LumioRoute.PAIR_REVIEW -> NavMotion.RISE
    else -> NavMotion.PUSH
}

/**
 * Whether [route] is a wallet page: the Wallet, its Account and an Asset. They drill in: the screen beneath holds its place under a scrim, and the page
 * slides in and out on a critically damped spring (the Phase 2.8 push targets). Every other route moves by its class.
 */
private fun isDrill(route: String?): Boolean = when (route) {
    LumioRoute.WALLET, LumioRoute.ACCOUNT, LumioRoute.ASSET -> true
    else -> false
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
    val drill = isDrill(route)
    composable(
        route = route,
        arguments = arguments,
        enterTransition = {
            cutWhenReduced(NavReducedMotion.reduced, EnterTransition.None) { if (drill) drillEnter() else enterOf(own) }
        },
        exitTransition = {
            cutWhenReduced(NavReducedMotion.reduced, ExitTransition.None) {
                if (isDrill(targetState.destination.route)) drillScrim() else exitTowards(navMotionOf(targetState.destination.route))
            }
        },
        popEnterTransition = {
            cutWhenReduced(NavReducedMotion.reduced, EnterTransition.None) {
                if (isDrill(initialState.destination.route)) drillScrimLift() else popEnterFrom(navMotionOf(initialState.destination.route))
            }
        },
        popExitTransition = {
            cutWhenReduced(NavReducedMotion.reduced, ExitTransition.None) { if (drill) drillBackExit() else popExitOf(own) }
        },
    ) { entry ->
        val scope = this
        CompositionLocalProvider(LocalNavScope provides scope) { scope.content(entry) }
    }
}

/**
 * The spring of the wallet pages' drill. It is critically damped, so a page settles within about 300 ms and does not overshoot, and the alpha and the
 * position share it so they land together.
 */
private val drillSpring = spring<Float>(dampingRatio = 1f, stiffness = 400f)

/** The same spring for positions: the offsets of the drill's slides. */
private val drillOffset = spring<IntOffset>(dampingRatio = 1f, stiffness = 400f)

/** The alpha a screen keeps under the scrim of a pushed wallet page: the scrim takes the other 40%. */
private const val DRILL_SCRIM_ALPHA = 0.6f

/** A wallet page slides in from the right edge. It is in place on its first frame, and nothing fades in over the screen beneath it. */
private fun drillEnter(): EnterTransition = slideInHorizontally(drillOffset) { it }

/** The screen beneath a pushed wallet page holds its place and dims under the scrim. It neither moves nor scales. */
private fun drillScrim(): ExitTransition = fadeOut(drillSpring, targetAlpha = DRILL_SCRIM_ALPHA)

/** A popped wallet page slides out to the right edge. */
private fun drillBackExit(): ExitTransition = slideOutHorizontally(drillOffset) { it }

/** The screen that a pop reveals lifts its scrim: it is in place on its first frame, and its alpha rises to full. */
private fun drillScrimLift(): EnterTransition = fadeIn(drillSpring, initialAlpha = DRILL_SCRIM_ALPHA)
