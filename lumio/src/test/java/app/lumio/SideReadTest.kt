package app.lumio

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.network.NetworkMode
import app.lumio.network.NetworkModeStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The saved side is read before a screen shows its networks: the first frame holds the read shut, and the read opens only once the owner's side is set,
 * so no screen shows the default side first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = LumioApp::class)
class SideReadTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    private val session by lazy {
        VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 7 + 3).toByte() })
    }

    @Test
    fun theReadStaysShutUntilTheSavedSideIsSet() {
        NetworkModeStore.write(session, NetworkMode.TESTNET)
        val selection = NetworkSelection()
        var firstFrame: Boolean? = null
        var sideWhenOpen: NetworkMode? = null
        compose.setContent {
            val read = rememberSideRead(session, selection)
            if (firstFrame == null) firstFrame = read
            if (read) sideWhenOpen = selection.mode
        }

        compose.waitUntil(timeoutMillis = 5_000) { sideWhenOpen != null }
        assertEquals(false, firstFrame)
        assertEquals(NetworkMode.TESTNET, sideWhenOpen)
    }
}
