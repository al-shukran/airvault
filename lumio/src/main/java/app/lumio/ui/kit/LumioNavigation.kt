package app.lumio.ui.kit

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.lensly.ui.navigation.LenslyNavTransitions
import app.lumio.LumioRoute
import app.lumio.ui.theme.LumioMotion

/** The shared-element scope of the NavHost. [app.lumio.LumioNavHost] provides it, and it is null outside the NavHost. */
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The visibility scope of the screen on show, which [lumioComposable] provides. A shared element animates with it. */
val LocalNavScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * The class of a screen's transition (section 3.2). The Wallets root has no class of its own: it keeps Lensly's transitions when the gate opens it,
 * and it takes the class of the screen that comes back to it.
 */
internal enum class NavMotion { ROOT, PUSH, RISE, FADE }

/**
 * The class of the route pattern [route] (section 2): Settings is the Fade, the send and pairing sheets are the Rises, and every other Lumio screen is a
 * Push. Null when there is no route.
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
        enterTransition = { enterOf(own) },
        exitTransition = { exitTowards(navMotionOf(targetState.destination.route)) },
        popEnterTransition = { popEnterFrom(navMotionOf(initialState.destination.route)) },
        popExitTransition = { popExitOf(own) },
    ) { entry ->
        val scope = this
        CompositionLocalProvider(LocalNavScope provides scope) { scope.content(entry) }
    }
}

private typealias NavTransitionScope = AnimatedContentTransitionScope<NavBackStackEntry>

/** The distance a Rise screen scales from, and the scale a screen beneath it goes to (section 3.2). */
private const val RISE_SCALE = 0.94f

/** The spatial spring as an offset: the bounds and slides of section 3.1. */
private val spatialOffset = spring<IntOffset>(dampingRatio = 0.86f, stiffness = 380f)

/** How a screen comes in when it is pushed. The Wallets root, which has no class, keeps Lensly's enter. */
private fun NavTransitionScope.enterOf(motion: NavMotion): EnterTransition = when (motion) {
    NavMotion.PUSH -> pushEnter()
    NavMotion.RISE -> riseEnter()
    NavMotion.FADE -> fadeThroughEnter()
    NavMotion.ROOT -> LenslyNavTransitions.enter(this)
}

/** How a screen goes out when a screen of class [target] is pushed over it. A Rise makes the screen beneath it scale back. */
private fun NavTransitionScope.exitTowards(target: NavMotion?): ExitTransition = when (target) {
    NavMotion.RISE -> riseUnderExit()
    NavMotion.FADE -> fadeOutQuick()
    else -> pushExit()
}

/** How a screen comes back when the screen of class [popped] over it is popped. The revealed screen takes the shape of the one that leaves. */
private fun NavTransitionScope.popEnterFrom(popped: NavMotion?): EnterTransition = when (popped) {
    NavMotion.RISE -> riseUnderEnter()
    NavMotion.FADE -> fadeThroughEnter()
    NavMotion.PUSH -> pushBackEnter()
    NavMotion.ROOT, null -> LenslyNavTransitions.popEnter(this)
}

/** How a screen of class [motion] goes out when it is popped. */
private fun NavTransitionScope.popExitOf(motion: NavMotion): ExitTransition = when (motion) {
    NavMotion.PUSH -> pushBackExit()
    NavMotion.RISE -> riseBackExit()
    NavMotion.FADE -> fadeOutQuick()
    NavMotion.ROOT -> LenslyNavTransitions.popExit(this)
}

private fun pushEnter(): EnterTransition =
    fadeIn(tween(LumioMotion.MEDIUM, delayMillis = 40, easing = LumioMotion.EmphDecel)) +
        slideInHorizontally(spatialOffset) { it / 12 }

private fun pushExit(): ExitTransition = fadeOut(tween(90)) + slideOutHorizontally(spatialOffset) { -it / 24 }

private fun pushBackEnter(): EnterTransition =
    fadeIn(tween(LumioMotion.MEDIUM, delayMillis = 40, easing = LumioMotion.EmphDecel)) +
        slideInHorizontally(spatialOffset) { -it / 24 }

private fun pushBackExit(): ExitTransition = fadeOut(tween(90)) + slideOutHorizontally(spatialOffset) { it / 12 }

private fun riseEnter(): EnterTransition = slideInVertically(spatialOffset) { it / 8 } + fadeIn(tween(LumioMotion.MEDIUM))

private fun riseUnderExit(): ExitTransition =
    scaleOut(LumioMotion.spatial, targetScale = RISE_SCALE) + fadeOut(tween(LumioMotion.MEDIUM))

private fun riseUnderEnter(): EnterTransition =
    scaleIn(LumioMotion.spatial, initialScale = RISE_SCALE) + fadeIn(tween(LumioMotion.MEDIUM))

private fun riseBackExit(): ExitTransition = slideOutVertically(spatialOffset) { it / 8 } + fadeOut(tween(LumioMotion.MEDIUM))

/** The fade-through of the Settings root (section 3.2): the old screen fades quickly, and the new one fades in and grows from 0.96 after it. */
private fun fadeThroughEnter(): EnterTransition =
    fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.96f)

private fun fadeOutQuick(): ExitTransition = fadeOut(tween(90))
