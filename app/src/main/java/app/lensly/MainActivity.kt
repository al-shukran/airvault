package app.lensly

import androidx.compose.runtime.Composable
import app.lensly.ui.CoverActivity
import app.lensly.ui.navigation.LenslyNavHost

/** Lensly's activity. The shared [CoverActivity] sets the window up. This supplies Lensly's navigation. */
class MainActivity : CoverActivity() {
    @Composable
    override fun CoverContent(debugHiddenRequest: Int) {
        LenslyNavHost(debugHiddenRequest = debugHiddenRequest)
    }
}
