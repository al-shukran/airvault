package app.lumio.ui.transactions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.components.groupAddress
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.transactions.TxDetail
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The states of the transaction page: its title, a pending and a failed status, an unknown counterparty, and the explorer's question. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransactionDetailStatesTest {
    @get:Rule
    val compose = createComposeRule()

    private val network = NetworkId(Networks.evm(1), testnet = false)
    private val account = "0x" + "a1".repeat(20)
    private val friend = "0x" + "5c".repeat(20)

    private fun detail(
        status: TxStatus = TxStatus.CONFIRMED,
        direction: TxDirection = TxDirection.OUT,
        explorerUrl: String? = null,
    ): TxDetail = TxDetail(
        tx = LumioTx(
            id = "tx-1",
            network = network,
            account = account,
            hash = "01".repeat(32),
            timestampMs = 1_700_000_000_000L,
            asset = TxAsset(contract = null, symbol = "ETH", name = "Ether", decimals = 18),
            amount = BigInteger.TEN.pow(17),
            direction = direction,
            fee = null,
            counterparty = friend,
            status = status,
            confirmations = 0L,
            inputData = null,
            memo = null,
        ),
        usd = null,
        comment = null,
        chain = "Ethereum",
        feeText = null,
        explorerUrl = explorerUrl,
        flags = emptyList(),
    )

    private fun show(detail: TxDetail, onAddress: (String) -> Unit = {}, onExplorer: () -> Unit = {}) {
        compose.setContent {
            ClearAirTheme {
                TransactionDetailScreen(
                    detail = detail,
                    accountNames = mapOf(account to "PulsarNova #5"),
                    book = emptyList(),
                    backLabel = "Activity",
                    showBalance = true,
                    onAddress = onAddress,
                    onExplorer = onExplorer,
                    onBack = {},
                    onLock = {},
                )
            }
        }
    }

    @Test
    fun thePageIsTitledByTheTransaction() {
        show(detail())

        // The title is both the page's top bar and the status hero, so it is on the screen twice.
        assertTrue(compose.onAllNodesWithText("Sent", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aPendingTransactionSaysItIsConfirming() {
        show(detail(status = TxStatus.PENDING))

        compose.onNodeWithText("Confirming", substring = true).assertIsDisplayed()
    }

    @Test
    fun aFailedTransactionSaysItFailed() {
        show(detail(status = TxStatus.FAILED))

        compose.onNodeWithText("Failed", substring = true).assertIsDisplayed()
    }

    @Test
    fun anUnknownCounterpartyInvitesItsAddition() {
        show(detail())

        compose.onNodeWithText("Not in Address Book").assertIsDisplayed()
        compose.onNodeWithText("Tap to add it on Lensly").assertIsDisplayed()
    }

    @Test
    fun theAddressItselfOpensItsSheet() {
        val tapped = mutableListOf<String>()
        show(detail(), onAddress = { tapped += it })

        compose.onNodeWithText(groupAddress(friend)).performClick()

        assertEquals(listOf(friend), tapped)
    }

    @Test
    fun theExplorerAsksBeforeItOpens() {
        var opened = false
        show(detail(explorerUrl = "https://testnet.monadexplorer.com/tx/0x01"), onExplorer = { opened = true })

        compose.onNodeWithText("View on Explorer").performClick()
        compose.onNodeWithText("Open in Your Browser?").assertIsDisplayed()
        compose.onNodeWithText("testnet.monadexplorer.com").assertIsDisplayed()
        assertFalse(opened)

        compose.onNodeWithTag(TX_EXPLORER_TAG).performClick()
        assertTrue(opened)
    }
}
