package app.lensly.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.lensly.app.AddressBookEntry
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LumenTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every Address Book row names the chain family of its address beside the label. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class AddressBookScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyRowNamesTheChainOfItsAddressBesideTheLabel() {
        val entries = listOf(
            AddressBookEntry("e1", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "Alice", "Exchange deposit"),
            AddressBookEntry("e2", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", "Bob", null),
            AddressBookEntry("e3", "11111111111111111111111111111111", "Sol", null),
            AddressBookEntry("e4", "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx", "Test", "Faucet"),
        )
        compose.setContent { LumenTheme { AddressBookScreen(entries = entries, onOpen = {}, onAdd = {}, onScan = {}, onBack = {}) } }

        compose.onNodeWithText("Ethereum · Exchange deposit").assertIsDisplayed()
        compose.onNodeWithText("Bitcoin").assertIsDisplayed()
        compose.onNodeWithText("Solana").assertIsDisplayed()
        // A Bitcoin address is on one network, so a testnet4 entry is not listed as Bitcoin.
        compose.onNodeWithText("Bitcoin Testnet4 · Faucet").assertIsDisplayed()
    }
}
