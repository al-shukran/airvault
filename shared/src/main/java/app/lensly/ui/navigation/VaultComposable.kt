package app.lensly.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.runtime.Composable
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.lensly.ui.theme.LumenTheme

/**
 * A screen of Lensly's hidden area, in the Lumen theme. The activity sets the camera cover's theme at the root, so the cover and the gate
 * keep it and every route that goes through here takes Lumen, including the status and navigation bar icons.
 */
fun NavGraphBuilder.vaultComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route, arguments, deepLinks) { entry ->
        LumenTheme { content(entry) }
    }
}
