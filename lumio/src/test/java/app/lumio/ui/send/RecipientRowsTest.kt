package app.lumio.ui.send

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.ui.kit.ADDRESS_LINE_TAG
import app.lumio.ui.kit.spokenAddress
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.web3j.crypto.Keys

/** The Recipient page's rows (#1692): the line under a name is who it is, and its address is a line of its own, in checksum case for an EVM address. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecipientRowsTest {
    @get:Rule
    val compose = createComposeRule()

    private val lower = "0x94142b4f" + "0".repeat(24) + "c8046598"
    private val bitcoin = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"

    @Test
    fun eachRowSaysWhoItIsAndShowsItsAddressInGroupsOfFourInChecksumCase() {
        compose.setContent {
            ClearAirTheme {
                SendRecipientPage(
                    value = "",
                    onValueChange = {},
                    error = null,
                    hint = null,
                    canContinue = false,
                    accounts = listOf(SendChoice(title = "Long Term Savings", detail = "Your account", address = lower)),
                    addressBook = listOf(SendChoice(title = "Cold Storage", detail = "Address Book", address = bitcoin)),
                    onPick = {},
                    onScan = {},
                    onContinue = {},
                    backLabel = "Send",
                    offline = null,
                    onBack = {},
                    onLock = {},
                )
            }
        }

        compose.onNodeWithText("Your account", useUnmergedTree = true).assertIsDisplayed()
        val spoken = compose.onAllNodesWithTag(ADDRESS_LINE_TAG, useUnmergedTree = true).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].first().text }
        assertEquals(listOf(spokenAddress(Keys.toChecksumAddress(lower)).text, spokenAddress(bitcoin).text), spoken)
    }
}
