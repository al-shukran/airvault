package app.lumio.ui.theme

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowCompat

/** The Day screens on show. The icons stay dark while one is, so a screen that leaves cannot light the icons of the one that stays. */
private var daySurfacesOnShow = 0

/**
 * Sets the icons of the status and navigation bars for the theme in composition. The bars show the backdrop's colour, drawn edge to edge
 * behind them: the Day palette takes dark icons, and the Night palette and the camera and gate, which are always dark, take light icons.
 */
@Composable
internal fun SystemBarIcons(lightBackground: Boolean) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity, lightBackground) {
        if (lightBackground) daySurfacesOnShow++
        applyIcons(activity)
        onDispose {
            if (lightBackground) daySurfacesOnShow--
            applyIcons(activity)
        }
    }
}

private fun applyIcons(activity: Activity) {
    val darkIcons = daySurfacesOnShow > 0
    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    controller.isAppearanceLightStatusBars = darkIcons
    controller.isAppearanceLightNavigationBars = darkIcons
}
