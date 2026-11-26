package app.lumio.ui.home

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import java.io.ByteArrayOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** A token whose logo Lumio does not ship shows the logo the price source has for it, and its letter only when no source has one. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FetchedLogoTest {
    @get:Rule
    val compose = createComposeRule()

    private val ousg = AssetIconSpec.Fetched(NetworkId(Networks.evm(1), testnet = false), "0x1b19c19393e2d034d8ff31ff34c81252fcbbee92", "O")

    private fun png(): ByteArray = ByteArrayOutputStream().also { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private fun show(read: suspend (NetworkId, String) -> ByteArray?) {
        compose.setContent {
            CompositionLocalProvider(LocalTokenLogos provides read) { ClearAirTheme { AssetIcon(spec = ousg, size = 24.dp) } }
        }
        compose.waitForIdle()
    }

    @Test
    fun aTokenWithALogoAtTheSourceShowsThatLogoNotItsLetter() {
        val asked = mutableListOf<String>()
        show { _, contract -> asked += contract; png() }
        // The logo is read off the main thread, which the test clock does not wait for.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(FETCHED_LOGO_TAG).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag(FETCHED_LOGO_TAG).assertExists()
        compose.onNodeWithText("O").assertDoesNotExist()
        kotlin.test.assertEquals(listOf(ousg.contract), asked)
    }

    @Test
    fun aTokenNoSourceHasALogoForShowsItsLetter() {
        show { _, _ -> null }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("O").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("O").assertExists()
        compose.onNodeWithTag(FETCHED_LOGO_TAG).assertDoesNotExist()
    }
}
