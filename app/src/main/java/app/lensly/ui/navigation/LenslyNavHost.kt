package app.lensly.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.lensly.ui.camera.CameraScreen
import app.lensly.ui.camera.GalleryScreen
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.theme.LenslyBlack

object LenslyRoute {
    const val CAMERA = "camera"
    const val GALLERY = "gallery"
    const val HIDDEN = "hidden"
}

@Composable
fun LenslyNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    ReturnToCameraWhenBackgrounded(navController)
    NavHost(
        navController = navController,
        startDestination = LenslyRoute.CAMERA,
        modifier = modifier,
    ) {
        composable(LenslyRoute.CAMERA) {
            CameraScreen(
                onOpenGallery = { navController.navigate(LenslyRoute.GALLERY) { launchSingleTop = true } },
                onHiddenEntry = { navController.navigate(LenslyRoute.HIDDEN) { launchSingleTop = true } },
            )
        }
        composable(LenslyRoute.GALLERY) {
            GalleryScreen(onBack = { navController.popBackStack() })
        }
        composable(LenslyRoute.HIDDEN) {
            HiddenPlaceholder()
        }
    }
}

/** Placeholder until the hidden area is built. */
@Composable
private fun HiddenPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LenslyBlack),
        contentAlignment = Alignment.Center,
    ) {
        DotHeadline(text = "HIDDEN")
    }
}

/**
 * Whenever the app leaves the foreground, the back stack returns to the camera, so the app
 * always reopens on the camera. Configuration changes such as rotation also stop the activity,
 * so they are excluded.
 */
@Composable
private fun ReturnToCameraWhenBackgrounded(navController: NavHostController) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    DisposableEffect(lifecycleOwner, navController) {
        val observer = LifecycleEventObserver { _, event ->
            val rotating = context.findActivity()?.isChangingConfigurations == true
            if (event == Lifecycle.Event.ON_STOP && !rotating) {
                navController.navigate(LenslyRoute.CAMERA) {
                    popUpTo(LenslyRoute.CAMERA) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
