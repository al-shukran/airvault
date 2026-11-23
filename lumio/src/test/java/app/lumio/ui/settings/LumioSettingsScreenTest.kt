package app.lumio.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.data.config.FinishItem
import app.lumio.data.config.FinishSetup
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The top-level list of Lumio's settings: the Finish Setting Up checklist while it is open, one chevron row per page, each with its state under the title, a back arrow, and no Save. */
@RunWith(RobolectricTestRunner::class)
class LumioSettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val rows = listOf(
        SettingsRowState(SettingsPage.DISPLAY, "Total hidden · balances shown"),
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
        compose.setContent { ClearAirTheme { LumioSettingsScreen(rows = rows, finish = FinishSetup(), onFinish = {}, onOpen = {}, onReset = {}) } }

        for (row in rows) {
            compose.onNodeWithText(row.page.title).assertExists()
            compose.onNodeWithText(row.summary).assertExists()
        }
    }

    @Test
    fun theListHasNoSaveAndNoDeveloperRowInRelease() {
        compose.setContent { ClearAirTheme { LumioSettingsScreen(rows = rows, finish = FinishSetup(), onFinish = {}, onOpen = {}, onReset = {}) } }

        compose.onAllNodesWithText("Save", ignoreCase = true).assertCountEquals(0)
        compose.onNodeWithText("Developer").assertDoesNotExist()
    }

    @Test
    fun tappingARowOpensItsPage() {
        var opened: SettingsPage? = null
        compose.setContent {
            ClearAirTheme { LumioSettingsScreen(rows = rows, finish = FinishSetup(), onFinish = {}, onOpen = { opened = it }, onReset = {}) }
        }

        compose.onNodeWithText("Security").performScrollTo().performClick()

        assertEquals(SettingsPage.SECURITY, opened)
    }

    @Test
    fun theWalletsRowOpensTheWalletsPage() {
        var opened: SettingsPage? = null
        compose.setContent {
            ClearAirTheme { LumioSettingsScreen(rows = rows, finish = FinishSetup(), onFinish = {}, onOpen = { opened = it }, onReset = {}) }
        }

        compose.onNodeWithText("Wallets").performScrollTo().performClick()

        assertEquals(SettingsPage.WALLETS, opened)
    }

    @Test
    fun theChecklistCountsTheTickedItemsAndListsTheOpenOnes() {
        compose.setContent {
            ClearAirTheme { LumioSettingsScreen(rows = rows, finish = FinishSetup(setOf(FinishItem.PAIR)), onFinish = {}, onOpen = {}, onReset = {}) }
        }

        compose.onNodeWithText("Finish Setting Up").assertExists()
        compose.onNodeWithText("1 of 3").assertExists()
        compose.onNodeWithText("Set a Duress Password").assertExists()
        compose.onNodeWithText("Enable Device Protection").assertExists()
        compose.onNodeWithText("Pair with Lensly").assertDoesNotExist()
    }

    @Test
    fun tappingAnOpenChecklistItemOpensThePageWhereItIsDone() {
        var opened: FinishItem? = null
        compose.setContent {
            ClearAirTheme { LumioSettingsScreen(rows = rows, finish = FinishSetup(), onFinish = { opened = it }, onOpen = {}, onReset = {}) }
        }

        compose.onNodeWithText("Enable Device Protection").performScrollTo().performClick()

        assertEquals(FinishItem.DEVICE_PROTECTION, opened)
    }

    @Test
    fun theChecklistIsGoneOnceEveryItemIsTicked() {
        compose.setContent {
            ClearAirTheme {
                LumioSettingsScreen(rows = rows, finish = FinishSetup(FinishItem.entries.toSet()), onFinish = {}, onOpen = {}, onReset = {})
            }
        }

        compose.onNodeWithText("Finish Setting Up").assertDoesNotExist()
        compose.onNodeWithText("Security").assertExists()
    }
}
