package app.lumio.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.lumio.ui.screenshot.LUMIO_DARK_QUALIFIERS
import app.lumio.ui.theme.LumioTheme
import app.lensly.ui.screenshot.snapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The motion frames of the ring's progress (section 5, WP2): the ring is drawn while its progress runs from 0 to 1 over [RING_MS], and each golden is
 * the frame at the named share of that run. Reduced motion is off here, since the motion is the point, and the clock is paused, so each frame is exact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LumenRingFrameScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun ringProgress0() = frame(name = "ring_progress_0", atMs = 0L)

    @Test
    fun ringProgress50() = frame(name = "ring_progress_50", atMs = RING_MS / 2L)

    @Test
    fun ringProgress100() = frame(name = "ring_progress_100", atMs = RING_MS.toLong())

    private fun frame(name: String, atMs: Long) {
        compose.snapshot(
            name = name,
            beforeCapture = { mainClock.advanceTimeBy(atMs) },
            theme = { LumioTheme(content = it) },
        ) {
            val progress = remember { Animatable(0f) }
            LaunchedEffect(Unit) { progress.animateTo(1f, tween(RING_MS, easing = LinearEasing)) }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LumenRing(progress = progress.value, size = 96.dp)
            }
        }
    }

    private companion object {
        /** The length of the progress run, in milliseconds. */
        const val RING_MS = 1000
    }
}
