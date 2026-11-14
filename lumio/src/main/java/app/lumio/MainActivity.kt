package app.lumio

import androidx.compose.runtime.Composable
import app.lensly.ui.CoverActivity

/** Lumio's activity. The shared [CoverActivity] sets the window up. This supplies Lumio's navigation. */
class MainActivity : CoverActivity() {
    @Composable
    override fun CoverContent(debugHiddenRequest: Int) {
        LumioNavHost(debugHiddenRequest = debugHiddenRequest)
    }
}
