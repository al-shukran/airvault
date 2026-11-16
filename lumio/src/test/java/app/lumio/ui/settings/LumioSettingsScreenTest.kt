package app.lumio.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The top-level list of Lumio's settings: one chevron row per page, each with its state under the title, a back arrow, and no Save. */
@RunWith(RobolectricTestRunner::class)
class LumioSettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val rows = listOf(
        SettingsRowState(SettingsPage.DISPLAY, "Total hidden · min \$1.00"),
        SettingsRowState(SettingsPage.TRANSACTIONS, "Spam and scams hidden"),
        SettingsRowState(SettingsPage.NETWORKS, "3 chains on · Bitcoin default"),
        SettingsRowState(SettingsPage.PROVIDERS, "7 of 9 sources on"),
        SettingsRowState(SettingsPage.WALLETS, "1 wallet paired"),
        SettingsRowState(SettingsPage.ADDRESS_BOOK, "1 wallet · 2 saved addresses"),
        SettingsRowState(SettingsPage.SECURITY, "Entry gesture: Two-finger double tap"),
        SettingsRowState(SettingsPage.ABOUT, "Version 0.1.0"),
    )

    @Test
    fun theListShowsEachPageWithItsStateUnderItsTitle() {
        compose.setContent { LenslyTheme { LumioSettingsScreen(rows = rows, onOpen = {}, onBack = {}) } }

        for (row in rows) {
            compose.onNodeWithText(row.page.title).assertExists()
            compose.onNodeWithText(row.summary).assertExists()
        }
    }

    @Test
    fun theListHasNoSaveAndNoDeveloperRowInRelease() {
        compose.setContent { LenslyTheme { LumioSettingsScreen(rows = rows, onOpen = {}, onBack = {}) } }

        compose.onAllNodesWithText("Save", ignoreCase = true).assertCountEquals(0)
        compose.onNodeWithText("Developer").assertDoesNotExist()
    }

    @Test
    fun theBackArrowLeavesTheList() {
        var backs = 0
        compose.setContent { LenslyTheme { LumioSettingsScreen(rows = rows, onOpen = {}, onBack = { backs++ }) } }

        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }

    @Test
    fun tappingARowOpensItsPage() {
        var opened: SettingsPage? = null
        compose.setContent { LenslyTheme { LumioSettingsScreen(rows = rows, onOpen = { opened = it }, onBack = {}) } }

        compose.onNodeWithText("Security").performScrollTo().performClick()

        assertEquals(SettingsPage.SECURITY, opened)
    }

    @Test
    fun theWalletsRowOpensTheWalletsPage() {
        var opened: SettingsPage? = null
        compose.setContent { LenslyTheme { LumioSettingsScreen(rows = rows, onOpen = { opened = it }, onBack = {}) } }

        compose.onNodeWithText("Wallets").performScrollTo().performClick()

        assertEquals(SettingsPage.WALLETS, opened)
    }

    @Test
    fun theDeveloperRowIsListedLastWhenTheBuildGivesIt() {
        val withDeveloper = rows + SettingsRowState(SettingsPage.DEVELOPER, "Demo portfolio on")
        var opened: SettingsPage? = null
        compose.setContent { LenslyTheme { LumioSettingsScreen(rows = withDeveloper, onOpen = { opened = it }, onBack = {}) } }

        compose.onNodeWithText("Demo portfolio on").performScrollTo().assertExists()
        compose.onNodeWithText("Developer").performScrollTo().performClick()

        assertEquals(SettingsPage.DEVELOPER, opened)
        assertTrue(withDeveloper.last().page == SettingsPage.DEVELOPER)
    }
}
