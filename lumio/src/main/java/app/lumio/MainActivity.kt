package app.lumio

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import app.lensly.ui.CoverActivity

/** Lumio's activity. The shared [CoverActivity] sets the window up. This supplies Lumio's navigation. */
class MainActivity : CoverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugEvmFunds.offer(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DebugEvmFunds.offer(intent)
    }

    @Composable
    override fun CoverContent(debugHiddenRequest: Int) {
        LumioNavHost(debugHiddenRequest = debugHiddenRequest)
    }
}
