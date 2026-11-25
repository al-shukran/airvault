package app.lensly.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.lensly.ui.theme.LumenTheme

/**
 * A screen of Lensly's hidden area, in the Lumen theme, with the transition of its [motion] class. The
 * default is a Push. A [NavMotion.ROOT] screen keeps the NavHost's transition. The activity sets the camera cover's theme at the root, so the
 * cover and the gate keep it and every route that goes through here takes Lumen, including the status and navigation bar icons.
 */
fun NavGraphBuilder.vaultComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    motion: NavMotion = NavMotion.PUSH,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    val screen: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit = { entry ->
        LumenTheme {
            MirrorNavMotion()
            content(entry)
        }
    }
    if (motion == NavMotion.RISE) riseRoutes += route
    if (motion == NavMotion.ROOT) {
        composable(route = route, arguments = arguments, deepLinks = deepLinks, content = screen)
    } else {
        composable(
            route = route,
            arguments = arguments,
            deepLinks = deepLinks,
            enterTransition = { cutWhenReduced(NavReducedMotion.reduced, EnterTransition.None) { enterOf(motion) } },
            exitTransition = { cutWhenReduced(NavReducedMotion.reduced, ExitTransition.None) { LenslyNavTransitions.exit(this) } },
            popEnterTransition = { cutWhenReduced(NavReducedMotion.reduced, EnterTransition.None) { LenslyNavTransitions.popEnter(this) } },
            popExitTransition = { cutWhenReduced(NavReducedMotion.reduced, ExitTransition.None) { popExitOf(motion) } },
            content = screen,
        )
    }
}
