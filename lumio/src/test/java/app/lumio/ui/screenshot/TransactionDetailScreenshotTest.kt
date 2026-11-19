package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.transactions.TxDetail
import app.lumio.ui.transactions.TransactionDetailScreen
import java.math.BigDecimal
import java.math.BigInteger
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of one transaction's detail, a sent transaction and a flagged spam transaction, in the Lensly dark theme. Times are shown in UTC so that the
 * goldens read the same on every host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransactionDetailScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val ethereum = NetworkId(Networks.evm(1), testnet = false)
    private val me = "0x" + "a1".repeat(20)
    private val friend = "0x" + "5c".repeat(20)
    private val spammer = "0x" + "77".repeat(20)
    private val ether = TxAsset(contract = null, symbol = "ETH", name = "Ether", decimals = 18)
    private val nov = 1_700_000_000_000L

    private fun hashOf(n: Int): String = "0x" + n.toString(16).padStart(2, '0').repeat(32)

    private fun tx(
        n: Int,
        network: NetworkId,
        asset: TxAsset,
        amount: BigInteger,
        direction: TxDirection,
        counterparty: String?,
        timestampMs: Long,
        status: TxStatus = TxStatus.CONFIRMED,
        memo: String? = null,
    ) = LumioTx(
        id = "EVM:${network.chain}:${hashOf(n)}",
        network = network,
        account = me,
        hash = hashOf(n),
        timestampMs = timestampMs,
        asset = asset,
        amount = amount,
        direction = direction,
        fee = null,
        counterparty = counterparty,
        status = status,
        confirmations = if (status == TxStatus.PENDING) 0L else 120L,
        inputData = null,
        memo = memo,
    )

    private fun eth(whole: String): BigInteger = BigDecimal(whole).movePointRight(18).toBigIntegerExact()

    @Test
    fun transactionDetail() = compose.lumioSnapshot("transaction_detail") {
        TransactionDetailScreen(
            detail = TxDetail(
                tx = tx(1, ethereum, ether, eth("1.5"), TxDirection.IN, friend, nov),
                usd = 3450.0,
                comment = "Rent split for October",
                chain = "Ethereum",
                feeText = "0.00042 ETH",
                explorerUrl = "https://etherscan.io/tx/" + hashOf(1),
                flags = emptyList(),
            ),
            onBack = {},
            onExplorer = {},
            onSaveComment = {},
            zone = ZoneOffset.UTC,
        )
    }

    @Test
    fun transactionDetailFlaggedAsSpam() = compose.lumioSnapshot("transaction_detail_flagged") {
        val dust = tx(7, ethereum, TxAsset("0x" + "ab".repeat(20), "FREE", "Free claim token", 18), BigInteger.ONE, TxDirection.IN, spammer, nov)
        TransactionDetailScreen(
            detail = TxDetail(
                tx = dust,
                usd = null,
                comment = null,
                chain = "Ethereum",
                feeText = null,
                explorerUrl = null,
                flags = listOf("Moves no value", "Its name or symbol points to a link or a call to action"),
            ),
            onBack = {},
            onExplorer = {},
            onSaveComment = {},
            zone = ZoneOffset.UTC,
        )
    }
}
