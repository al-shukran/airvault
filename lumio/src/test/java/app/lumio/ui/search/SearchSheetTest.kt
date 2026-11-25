package app.lumio.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The search sheet's tab row names the tabs past its right edge, and the sheet has no close button of its own: the scrim and Back close it. */
@RunWith(RobolectricTestRunner::class)
class SearchSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tabsPastTheRightEdgeAreCountedByAMoreLabel() {
        compose.setContent {
            ClearAirTheme { Box(Modifier.width(360.dp)) { SearchSheet(query = "", onQuery = {}, tab = SearchTab.MAIN, onTab = {}, sections = emptyList()) } }
        }
        compose.waitForIdle()

        compose.onNode(hasText(" more", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Main").assertIsDisplayed()
    }

    @Test
    fun theSheetHasNoCloseButton() {
        compose.setContent { ClearAirTheme { SearchSheet(query = "", onQuery = {}, tab = SearchTab.MAIN, onTab = {}, sections = emptyList()) } }

        compose.onNodeWithContentDescription("Close").assertDoesNotExist()
    }
}
