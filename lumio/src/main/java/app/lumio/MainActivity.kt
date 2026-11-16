package app.lumio

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import app.lensly.app.vaultHolder
import app.lensly.ui.CoverActivity

/** Lumio's activity. The shared [CoverActivity] sets the window up. This supplies Lumio's navigation. */
class MainActivity : CoverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val firstInProcess = !createdInProcess
        createdInProcess = true
        // A new task in a process that is still running starts locked. The owner comes back through the gate, never into an open vault.
        if (!firstInProcess && savedInstanceState == null) applicationContext.vaultHolder().lock()
        // A process that the system restores had locked its vault when it died, so the saved navigation could show a screen the vault
        // cannot serve. The app reopens on the camera instead, as it does after any lock.
        super.onCreate(if (firstInProcess) null else savedInstanceState)
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

    private companion object {
        /** False until the first activity of this process has started. */
        var createdInProcess = false
    }
}
