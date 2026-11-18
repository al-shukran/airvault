package app.lumio.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.lumio.ui.home.LumioMark
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Lumen ring at 240 dp, so the goldens show the geometry that LumenRing reuses: gold on ink in Night, ink on paper in Day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LumioMarkScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun mark() = compose.lumioSnapshot("lumio_mark") { Mark() }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun markLight() = compose.lumioSnapshot("lumio_mark_light") { Mark() }
}

@Composable
private fun Mark() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LumioMark(size = 240.dp)
    }
}
