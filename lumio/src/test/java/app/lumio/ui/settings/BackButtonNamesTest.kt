package app.lumio.ui.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import app.lensly.ui.theme.ClearAirTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A page's back button is read by TalkBack as the place it returns to. A settings page returns to Settings. The Address Book opens from Settings and
 * from Search, so its back button says only Back, which is right from either place.
 */
@RunWith(RobolectricTestRunner::class)
class BackButtonNamesTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aSettingsPageBacksOutToSettings() {
        compose.setContent {
            ClearAirTheme {
                SettingsPageScreen(page = SettingsPage.SECURITY, offline = null, onBack = {}, onLock = {}) {}
            }
        }
        compose.onNodeWithContentDescription("Back to Settings").assertExists()
    }

    @Test
    fun theAddressBookBacksOutAsBack() {
        compose.setContent {
            ClearAirTheme {
                AddressBookScreen(walletName = "Air 1", entries = emptyList(), offline = null, onBack = {}, onLock = {})
            }
        }
        compose.onNodeWithContentDescription("Back").assertExists()
    }
}
