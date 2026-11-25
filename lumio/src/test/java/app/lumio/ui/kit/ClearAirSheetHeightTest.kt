package app.lumio.ui.kit

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LumenTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A sheet is as tall as its content, sits at the bottom, and never rises above the 44 dp gap under the top of the page. */
@RunWith(RobolectricTestRunner::class)
class ClearAirSheetHeightTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aShortContentSheetWrapsItsContentAtTheBottom() {
        compose.setContent { LumenTheme { ClearAirTheme { ClearAirSheet(visible = true, onClose = {}) { Box(Modifier.testTag("body").fillMaxWidth().height(100.dp)) } } } }
        val page = compose.onRoot().getBoundsInRoot()
        val body = compose.onNodeWithTag("body").getBoundsInRoot()
        assertEquals(page.bottom, body.bottom)
        assertEquals(100.dp, body.bottom - body.top)
        assertTrue(body.top > page.top + 300.dp, "the sheet is short, not full height: content top ${body.top}")
    }

    @Test
    fun aTallContentSheetStopsAtTheGapUnderThePageTop() {
        compose.setContent { LumenTheme { ClearAirTheme { ClearAirSheet(visible = true, onClose = {}) { Box(Modifier.testTag("body").fillMaxSize()) } } } }
        val page = compose.onRoot().getBoundsInRoot()
        val body = compose.onNodeWithTag("body").getBoundsInRoot()
        assertEquals(page.bottom, body.bottom)
        // The grab handle sits above the content, inside the 44 dp gap's remainder.
        assertTrue(body.top >= page.top + 44.dp && body.top < page.top + 64.dp, "content top ${body.top}")
    }
}
