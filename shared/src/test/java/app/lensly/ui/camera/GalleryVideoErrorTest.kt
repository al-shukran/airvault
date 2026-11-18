package app.lensly.ui.camera

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.CoverTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A video the store cannot open shows the app's own error state in the viewer, and no player starts. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class GalleryVideoErrorTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aDamagedVideoShowsTheAppsErrorState() {
        val store = keyedStore(folder.root)
        // Not a sealed video: the store refuses its header, so the viewer has nothing to play.
        store.directory.mkdirs()
        File(store.directory, "VID_1000.sealed").writeBytes(ByteArray(32))

        compose.setContent {
            CoverTheme {
                GalleryScreen(store = store, onBack = {})
            }
        }

        compose.onNodeWithContentDescription("Video").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("VIDEO CAN'T BE PLAYED").assertIsDisplayed()
    }
}
