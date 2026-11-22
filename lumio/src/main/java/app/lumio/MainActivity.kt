package app.lumio

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.lensly.app.vaultHolder
import app.lensly.ui.CoverActivity

/** Lumio's activity. The shared [CoverActivity] sets the window up. This supplies Lumio's navigation. */
class MainActivity : CoverActivity() {
    /** The debug demo seeds that have finished. Each one opens the hidden area as a debug launch asks for it, on the seeded wallet. */
    private var seeded by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        val firstInProcess = !createdInProcess
        createdInProcess = true
        // A new task in a process that is still running starts locked. The owner comes back through the gate, never into an open vault.
        if (!firstInProcess && savedInstanceState == null) applicationContext.vaultHolder().lock()
        // A process that the system restores had locked its vault when it died, so the saved navigation could show a screen the vault
        // cannot serve. The app reopens on the camera instead, as it does after any lock.
        super.onCreate(if (firstInProcess) null else savedInstanceState)
        DebugEvmFunds.offer(intent)
        seedIfAsked(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DebugEvmFunds.offer(intent)
        seedIfAsked(intent)
    }

    @Composable
    override fun CoverContent(debugHiddenRequest: Int) {
        LumioNavHost(debugHiddenRequest = debugHiddenRequest + seeded)
    }

    /**
     * A debug launch with the seed extra fills the demo vault first. Only then does it count as a launch that asks for the hidden area, so the
     * hidden area opens on the seeded wallet and not on the password gate. The release and benchmark stubs never ask.
     */
    private fun seedIfAsked(intent: Intent) {
        val mnemonic = DebugDemoSeed.mnemonicOf(intent) ?: return
        val app = application as LumioApp
        app.runInAppScope { DebugDemoSeed.seed(app, mnemonic) { runOnUiThread { seeded++ } } }
    }

    private companion object {
        /** False until the first activity of this process has started. */
        var createdInProcess = false
    }
}
