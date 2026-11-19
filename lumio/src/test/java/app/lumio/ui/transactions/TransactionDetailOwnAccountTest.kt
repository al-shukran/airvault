package app.lumio.ui.transactions

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.lensly.app.InternalAccount
import app.lensly.app.addressKeyOf
import app.lensly.ui.theme.LumenTheme
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.transactions.TxDetail
import java.math.BigInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The detail of a transaction names an address of a paired wallet's Ethereum account as that account, and leaves any other address unnamed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransactionDetailOwnAccountTest {

    @get:Rule
    val compose = createComposeRule()

    private val me = "0x" + "a1".repeat(20)
    private val friend = "0x" + "5c".repeat(20)
    private val hash = "0x" + "01".repeat(32)

    private fun tx(counterparty: String?) = LumioTx(
        id = "EVM:1:$hash",
        network = NetworkId(Networks.evm(1), testnet = false),
        account = me,
        hash = hash,
        timestampMs = 1_700_000_000_000L,
        asset = TxAsset(contract = null, symbol = "ETH", name = "Ether", decimals = 18),
        amount = BigInteger.TEN.pow(16),
        direction = TxDirection.OUT,
        fee = null,
        counterparty = counterparty,
        status = TxStatus.CONFIRMED,
        confirmations = 120L,
        inputData = null,
        memo = null,
    )

    @Test
    fun theAccountAndTheCounterpartyOfThePairedWalletAreNamed() {
        val accounts = mapOf(
            addressKeyOf(me) to InternalAccount(displayName = "Savings", autoName = "Vault 0", label = "Cold"),
            addressKeyOf(friend) to InternalAccount(displayName = null, autoName = "Vault 1", label = null),
        )

        setDetail(tx(counterparty = friend), accounts)

        compose.onNodeWithText("Your account · Savings · Cold").assertExists()
        compose.onNodeWithText("Your account · Vault 1").assertExists()
    }

    @Test
    fun anAddressOfNoPairedAccountHasNoName() {
        setDetail(tx(counterparty = friend), emptyMap())

        compose.onNodeWithText("Your account · Savings · Cold").assertDoesNotExist()
    }

    private fun setDetail(tx: LumioTx, accounts: Map<String, InternalAccount>) {
        compose.setContent {
            LumenTheme {
                TransactionDetailScreen(
                    detail = TxDetail(tx = tx, usd = null, comment = null, chain = "Ethereum", feeText = null, explorerUrl = null, flags = emptyList()),
                    onBack = {},
                    onExplorer = {},
                    onSaveComment = {},
                    accounts = accounts,
                )
            }
        }
    }
}
