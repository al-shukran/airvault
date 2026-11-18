package app.lumio.ui.transactions

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.app.AddressBookEntry
import app.lensly.ui.components.groupAddress
import app.lensly.ui.theme.LumenTheme
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.transactions.TxDetail
import java.math.BigInteger
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Every address of a transaction opens the address-book sheet, a Bitcoin address as an Ethereum one, and the name that the wallet's Address Book gives an
 * address is shown above it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransactionDetailAddressBookTest {

    @get:Rule
    val compose = createComposeRule()

    private val btcAccount = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
    private val btcCounterparty = "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"
    private val evmAccount = "0x" + "a1".repeat(20)
    private val evmCounterparty = "0x" + "5c".repeat(20)

    private fun tx(network: NetworkId, account: String, counterparty: String) = LumioTx(
        id = "${network.family.name}:${network.chain}:$account",
        network = network,
        account = account,
        hash = "01".repeat(32),
        timestampMs = 1_700_000_000_000L,
        asset = TxAsset(contract = null, symbol = "BTC", name = "Bitcoin", decimals = 8),
        amount = BigInteger.valueOf(21_000),
        direction = TxDirection.OUT,
        fee = null,
        counterparty = counterparty,
        status = TxStatus.CONFIRMED,
        confirmations = 6L,
        inputData = null,
        memo = null,
    )

    @Test
    fun everyBitcoinAddressOpensTheAddressBookSheetOfItself() {
        val tapped = mutableListOf<String>()
        setDetail(tx(NetworkId(Family.BTC, "mainnet", testnet = false), btcAccount, btcCounterparty), emptyList()) { tapped += it }

        compose.onNodeWithText(groupAddress(btcAccount)).performClick()
        compose.onNodeWithText(groupAddress(btcCounterparty)).performClick()

        assertEquals(listOf(btcAccount, btcCounterparty), tapped)
    }

    @Test
    fun anEthereumAddressStillOpensTheAddressBookSheet() {
        val tapped = mutableListOf<String>()
        setDetail(tx(NetworkId(Family.EVM, "1", testnet = false), evmAccount, evmCounterparty), emptyList()) { tapped += it }

        compose.onNodeWithText(groupAddress(evmCounterparty)).performClick()

        assertEquals(listOf(evmCounterparty), tapped)
    }

    @Test
    fun aBitcoinAddressShowsTheNameOfItsEntry() {
        val book = listOf(AddressBookEntry(id = "n1", address = btcCounterparty, name = "Landlord", label = null))

        setDetail(tx(NetworkId(Family.BTC, "mainnet", testnet = false), btcAccount, btcCounterparty), book) {}

        compose.onNodeWithText("Landlord").assertExists()
    }

    private fun setDetail(tx: LumioTx, book: List<AddressBookEntry>, onEdit: (String) -> Unit) {
        compose.setContent {
            LumenTheme {
                TransactionDetailScreen(
                    detail = TxDetail(tx = tx, usd = null, comment = null, chain = "Bitcoin", feeText = null, explorerUrl = null, flags = emptyList()),
                    onBack = {},
                    onExplorer = {},
                    onSaveComment = {},
                    book = book,
                    onEditAddress = onEdit,
                )
            }
        }
    }
}
