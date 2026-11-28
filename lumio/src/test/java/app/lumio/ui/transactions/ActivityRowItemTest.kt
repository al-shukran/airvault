package app.lumio.ui.transactions

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.app.addressKeyOf
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.transactions.TxFeedItem
import app.lumio.ui.kit.ADDRESS_LINE_TAG
import app.lumio.ui.kit.spokenAddress
import java.math.BigInteger
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** An activity row: a pending one leads its line with "Confirming", and a settled one says nothing of it. A tap opens the transaction. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityRowItemTest {
    @get:Rule
    val compose = createComposeRule()

    private val account = "0x" + "a1".repeat(20)
    private val friend = "0x" + "5c".repeat(20)

    private fun item(status: TxStatus): TxFeedItem = TxFeedItem(
        tx = LumioTx(
            id = "tx-$status",
            network = NetworkId(Networks.evm(1), testnet = false),
            account = account,
            hash = "02".repeat(32),
            timestampMs = 1_700_000_000_000L,
            asset = TxAsset(contract = null, symbol = "ETH", name = "Ether", decimals = 18),
            amount = BigInteger.TEN.pow(17),
            direction = TxDirection.IN,
            fee = null,
            counterparty = friend,
            status = status,
            confirmations = 0L,
            inputData = null,
            memo = null,
        ),
        chain = "Ethereum",
    )

    @Test
    fun aPendingRowSaysItIsConfirmingAndOpensOnTap() {
        var opened = 0
        compose.setContent {
            ClearAirTheme {
                ActivityRowItem(
                    item = item(TxStatus.PENDING),
                    names = emptyMap(),
                    zone = ZoneId.of("UTC"),
                    shown = true,
                    onClick = { opened++ },
                )
            }
        }

        compose.onNodeWithText("Confirming", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Received").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun aSettledRowDoesNotSayItIsConfirming() {
        compose.setContent {
            ClearAirTheme {
                ActivityRowItem(
                    item = item(TxStatus.CONFIRMED),
                    names = emptyMap(),
                    zone = ZoneId.of("UTC"),
                    shown = true,
                    onClick = {},
                )
            }
        }

        compose.onNodeWithText("Received").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Confirming", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun anUnnamedPartyIsFromItsAddressOnALineOfItsOwn() {
        compose.setContent {
            ClearAirTheme {
                ActivityRowItem(item = item(TxStatus.CONFIRMED), names = emptyMap(), zone = ZoneId.of("UTC"), shown = true, onClick = {})
            }
        }

        compose.onNodeWithText("From").assertIsDisplayed()
        val spoken = compose.onNodeWithTag(ADDRESS_LINE_TAG, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].first().text
        assertEquals(spokenAddress(friend).text, spoken)
        compose.onAllNodesWithText("0x", substring = true, useUnmergedTree = true).assertCountEquals(1)
        assertTrue(compose.onNodeWithTag(ADDRESS_LINE_TAG, useUnmergedTree = true).getBoundsInRoot().top > compose.onNodeWithText("Received", useUnmergedTree = true).getBoundsInRoot().bottom)
    }

    @Test
    fun aNamedPartyHasNoAddressLine() {
        compose.setContent {
            ClearAirTheme {
                ActivityRowItem(item = item(TxStatus.CONFIRMED), names = mapOf(addressKeyOf(friend) to "Lena Park"), zone = ZoneId.of("UTC"), shown = true, onClick = {})
            }
        }

        compose.onNodeWithText("From Lena Park", substring = true).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag(ADDRESS_LINE_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}
