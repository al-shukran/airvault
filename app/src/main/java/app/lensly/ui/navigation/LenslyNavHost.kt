package app.lensly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.lensly.ui.screens.CameraScreen

object LenslyRoute {
    const val CAMERA = "camera"
}

@Composable
fun LenslyNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = LenslyRoute.CAMERA,
        modifier = modifier,
    ) {
        composable(LenslyRoute.CAMERA) {
            CameraScreen()
        }
    }
}
