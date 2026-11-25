package app.lumio.ui.search

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import app.lensly.ui.theme.ClearAirTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The search sheet has no close button of its own, as the mockup has none: the scrim and Back close it. */
@RunWith(RobolectricTestRunner::class)
class SearchSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theSheetHasNoCloseButton() {
        compose.setContent { ClearAirTheme { SearchSheet(query = "", onQuery = {}, tab = SearchTab.MAIN, onTab = {}, sections = emptyList()) } }

        compose.onNodeWithContentDescription("Close").assertDoesNotExist()
    }
}
