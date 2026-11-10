package app.lensly

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.lensly.ui.applySecureWindow
import app.lensly.ui.navigation.LenslyNavHost
import app.lensly.ui.theme.LenslyTheme

class MainActivity : ComponentActivity() {
    /** Counts debug launches that ask for the hidden area. Always zero in release builds. */
    private var debugHiddenRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applySecureWindow()

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.BLACK),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.BLACK),
        )

        // A recreated activity (rotation) still has the launch intent, so only a fresh launch counts.
        if (savedInstanceState == null) requestDebugHidden(intent)

        setContent {
            LenslyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    LenslyNavHost(debugHiddenRequest = debugHiddenRequest)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestDebugHidden(intent)
    }

    private fun requestDebugHidden(intent: Intent) {
        if (DebugLaunch.opensHidden(intent)) debugHiddenRequest++
    }
}
