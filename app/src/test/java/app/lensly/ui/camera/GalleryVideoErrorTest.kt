package app.lensly.ui.camera

import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * A video the player cannot decode shows the app's own error state. The player's error listener consumes the failure, so
 * the system's "can't play" dialog never appears.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class GalleryVideoErrorTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun View.findVideoView(): VideoView? {
        if (this is VideoView) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).findVideoView()?.let { return it }
            }
        }
        return null
    }

    @Test
    fun aCorruptVideoShowsTheAppsErrorStateAndNotTheSystemDialog() {
        val store = PhotoStore.of(RuntimeEnvironment.getApplication())
        // Not an MP4: the player cannot read this container.
        store.newVideoFile(nowMs = 1_000L).apply { writeBytes(ByteArray(32)) }

        compose.setContent {
            LenslyTheme {
                GalleryScreen(onBack = {})
            }
        }

        compose.onNodeWithContentDescription("Video").performClick()
        compose.waitForIdle()

        val player = compose.activity.window.decorView.findVideoView()
        assertNotNull(player, "the viewer opened no video player")
        val listener = assertNotNull(Shadows.shadowOf(player).onErrorListener, "the player has no error listener")

        // The error a corrupt file causes when the player opens it. Handled means the system dialog stays away.
        assertTrue(listener.onError(null, 1, 0))

        compose.onNodeWithText("VIDEO CAN'T BE PLAYED").assertIsDisplayed()
    }
}
