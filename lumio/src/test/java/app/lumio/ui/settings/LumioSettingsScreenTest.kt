package app.lumio.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The top-level list of Lumio's settings: one chevron row per page, each with its state under the title, and no Save. */
@RunWith(RobolectricTestRunner::class)
class LumioSettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val rows = listOf(
        SettingsRowState(SettingsPage.DISPLAY, "Total hidden · min \$1.00"),
        SettingsRowState(SettingsPage.TRANSACTIONS, "Spam and scams hidden"),
        SettingsRowState(SettingsPage.NETWORKS, "3 chains on · Bitcoin default"),
        SettingsRowState(SettingsPage.PROVIDERS, "7 of 9 sources on"),
        SettingsRowState(SettingsPage.ADDRESS_BOOK, "1 wallet · 2 saved addresses"),
        SettingsRowState(SettingsPage.SECURITY, "Entry gesture: Two-finger double tap"),
        SettingsRowState(SettingsPage.ABOUT, "Version 0.1.0"),
    )

    @Test
    fun theListShowsEachPageWithItsStateUnderItsTitle() {
        compose.setContent { LenslyTheme { LumioSettingsScreen(rows = rows, onOpen = {}) } }

        for (row in rows) {
            compose.onNodeWithText(row.page.title).assertExists()
            compose.onNodeWithText(row.summary).assertExists()
        }
    }

    @Test
    fun theListHasNoSaveAndNoDeveloperRowInRelease() {
        compose.setContent { LenslyTheme { LumioSettingsScreen(rows = rows, onOpen = {}) } }

        compose.onAllNodesWithText("Save", ignoreCase = true).assertCountEquals(0)
        compose.onNodeWithText("Developer").assertDoesNotExist()
    }

    @Test
    fun tappingARowOpensItsPage() {
        var opened: SettingsPage? = null
        compose.setContent { LenslyTheme { LumioSettingsScreen(rows = rows, onOpen = { opened = it }) } }

        compose.onNodeWithText("Security").performScrollTo().performClick()

        assertEquals(SettingsPage.SECURITY, opened)
    }

    @Test
    fun theDeveloperRowIsListedLastWhenTheBuildGivesIt() {
        val withDeveloper = rows + SettingsRowState(SettingsPage.DEVELOPER, "Demo portfolio on")
        var opened: SettingsPage? = null
        compose.setContent { LenslyTheme { LumioSettingsScreen(rows = withDeveloper, onOpen = { opened = it }) } }

        compose.onNodeWithText("Demo portfolio on").performScrollTo().assertExists()
        compose.onNodeWithText("Developer").performScrollTo().performClick()

        assertEquals(SettingsPage.DEVELOPER, opened)
    }
}
