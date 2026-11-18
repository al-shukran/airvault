package app.lensly.ui.camera

import android.media.MediaMetadataRetriever
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource

/** A saved video is listed in the gallery beside the photos, with its length on its tile. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class GalleryVideoTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun galleryListsAVideoWithItsLength() {
        val store = keyedStore(folder.root)
        val recording = folder.newFile("recording.mp4").apply { writeBytes(ByteArray(8)) }
        assertTrue(store.saveVideo(recording, nowMs = 2_000L))
        // Robolectric keys every media source by one name, so the length is set on the stub's key.
        ShadowMediaMetadataRetriever.addMetadata(
            DataSource.toDataSource(stubMediaSource()),
            MediaMetadataRetriever.METADATA_KEY_DURATION,
            "7400",
        )

        compose.setContent {
            LenslyTheme {
                GalleryScreen(store = store, onBack = {})
            }
        }

        compose.onNodeWithContentDescription("Video").assertIsDisplayed()
        // The length is read off the main thread, so wait for the badge to appear.
        compose.waitUntil(timeoutMillis = 5_000L) {
            compose.onAllNodesWithText("0:07").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("0:07").assertIsDisplayed()
    }
}
