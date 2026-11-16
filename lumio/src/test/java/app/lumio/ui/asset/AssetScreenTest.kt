package app.lumio.ui.asset

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.price.ChartRange
import app.lumio.data.price.PricePoint
import app.lumio.data.price.PriceSeries
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.home.AccountOption
import app.lumio.home.HomeAsset
import app.lumio.home.HomeFormat
import app.lumio.home.Masking
import app.lumio.home.NativeAsset
import app.lumio.home.PickTarget
import app.lumio.home.TokenAsset
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The asset page's state: a hidden balance shows only the mask, a coin with no price data shows the empty chart, a token is sent from its accounts
 * like a coin, and a single account is taken at once. The page is shown with fixed data, and no test reaches a network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class AssetScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val ethNet = NetworkId(Family.EVM, "1", testnet = false)
    private val usdc = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    private val eth = NativeAsset("EVM-1", "ETH", "Ethereum", ethNet, BigDecimal("1.5"), 2746.88, 1.25)
    private val token = TokenAsset(
        id = TokenAsset.idOf(ethNet, usdc),
        symbol = "USDC",
        name = "Ethereum",
        network = ethNet,
        contract = usdc,
        balance = BigDecimal("1250.5"),
        usd = 1250.5,
        change24h = 0.02,
    )
    private val wallet = AccountOption(PickTarget.Evm("wallet-1"), "Ethereum", "Demo")
    private val series = PriceSeries(
        listOf(PricePoint(1_000L, 2_000.0), PricePoint(2_000L, 2_500.0), PricePoint(3_000L, 2_250.0)),
    )

    @Test
    fun aHiddenBalanceAndValueShowOnlyTheMask() {
        show(asset = eth, hidden = true)

        compose.onNodeWithTag(ASSET_BALANCE_TAG).assertTextEquals(Masking.MASK)
        compose.onNodeWithTag(ASSET_USD_TAG).assertTextEquals(Masking.MASK)
    }

    @Test
    fun aShownBalanceAndValueShowTheirDigits() {
        show(asset = eth, hidden = false)

        compose.onNodeWithTag(ASSET_BALANCE_TAG).assertTextEquals("1.5 ETH")
        compose.onNodeWithTag(ASSET_USD_TAG).assertTextEquals("\$2,746.88")
    }

    @Test
    fun aHiddenBalanceAlsoMasksTheAmountsOfTheTransactions() {
        show(asset = eth, hidden = true, transactions = listOf(tx(amount = "500000000000000000", direction = TxDirection.IN)))

        compose.onNodeWithText("+0.5 ETH").assertDoesNotExist()
        compose.onAllNodesWithText(Masking.MASK).assertCountEquals(3)
    }

    @Test
    fun aCoinWithNoPriceDataShowsTheEmptyChartAndNoChart() {
        show(asset = eth.copy(usd = null, change24h = null), series = null)

        compose.onNodeWithTag(ASSET_CHART_EMPTY_TAG).assertTextEquals("No price data for this range.")
        compose.onNodeWithTag(ASSET_CHART_TAG).assertDoesNotExist()
        compose.onNodeWithTag(ASSET_USD_TAG).assertTextEquals("—")
    }

    @Test
    fun aSeriesWithNoPointsIsNoPriceDataEither() {
        show(asset = eth, series = PriceSeries(emptyList()))

        compose.onNodeWithTag(ASSET_CHART_EMPTY_TAG).assertTextEquals("No price data for this range.")
        compose.onNodeWithTag(ASSET_CHART_TAG).assertDoesNotExist()
    }

    @Test
    fun aChartThatIsStillBeingReadSaysSoInsteadOfNoData() {
        show(asset = eth, series = null, chartLoading = true)

        compose.onNodeWithTag(ASSET_CHART_EMPTY_TAG).assertTextEquals("Loading the price chart.")
    }

    @Test
    fun aSeriesIsDrawnWithItsLowAndHighPrices() {
        show(asset = eth, series = series)

        compose.onNodeWithTag(ASSET_CHART_TAG).assertExists()
        compose.onNodeWithText("Low ${HomeFormat.price(2_000.0)}").assertExists()
        compose.onNodeWithText("High ${HomeFormat.price(2_500.0)}").assertExists()
        compose.onNodeWithTag(ASSET_CHART_EMPTY_TAG).assertDoesNotExist()
    }

    @Test
    fun aCoinWithNoTransactionsSaysSo() {
        show(asset = eth, transactions = emptyList())

        compose.onNodeWithTag(ASSET_TX_EMPTY_TAG).assertTextEquals("No transactions for this coin yet.")
    }

    @Test
    fun aTokenIsSentFromTheAccountThatHoldsIt() {
        var sent: AccountOption? = null
        show(asset = token, sendOptions = listOf(wallet), onSend = { sent = it })

        compose.onNodeWithTag(ASSET_SEND_TAG).performClick()

        assertEquals(wallet, sent)
    }

    @Test
    fun aTokenWithNoAccountThatCanSendItHasNoSendAction() {
        show(asset = token, sendOptions = emptyList(), receiveOptions = listOf(wallet))

        compose.onNodeWithTag(ASSET_SEND_TAG).assertIsNotEnabled()
    }

    @Test
    fun aSingleSendAccountIsTakenAtOnce() {
        var sent: AccountOption? = null
        show(asset = eth, sendOptions = listOf(wallet), onSend = { sent = it })

        compose.onNodeWithTag(ASSET_SEND_TAG).performClick()

        assertEquals(wallet, sent)
    }

    @Test
    fun aCoinWithNoAccountToReceiveToHasNoReceiveAction() {
        show(asset = eth, receiveOptions = emptyList())

        compose.onNodeWithTag(ASSET_RECEIVE_TAG).assertIsNotEnabled()
    }

    @Composable
    private fun Screen(
        asset: HomeAsset,
        hidden: Boolean,
        series: PriceSeries?,
        chartLoading: Boolean,
        transactions: List<LumioTx>,
        sendOptions: List<AccountOption>,
        receiveOptions: List<AccountOption>,
        onSend: (AccountOption) -> Unit,
    ) {
        LenslyTheme {
            AssetScreen(
                asset = asset,
                entry = null,
                hidden = hidden,
                range = ChartRange.H24,
                onRange = {},
                series = series,
                chartLoading = chartLoading,
                transactions = transactions,
                sendOptions = sendOptions,
                receiveOptions = receiveOptions,
                accounts = emptyList(),
                onSend = onSend,
                onReceive = {},
                onAccount = {},
                onBack = {},
                onLock = {},
            )
        }
    }

    private fun show(
        asset: HomeAsset,
        hidden: Boolean = false,
        series: PriceSeries? = null,
        chartLoading: Boolean = false,
        transactions: List<LumioTx> = emptyList(),
        sendOptions: List<AccountOption> = listOf(wallet),
        receiveOptions: List<AccountOption> = listOf(wallet),
        onSend: (AccountOption) -> Unit = {},
    ) {
        compose.setContent {
            Screen(
                asset = asset,
                hidden = hidden,
                series = series,
                chartLoading = chartLoading,
                transactions = transactions,
                sendOptions = sendOptions,
                receiveOptions = receiveOptions,
                onSend = onSend,
            )
        }
    }

    private fun tx(amount: String, direction: TxDirection): LumioTx = LumioTx(
        id = "tx-1",
        network = ethNet,
        account = "0x" + "a1".repeat(20),
        hash = "0x" + "11".repeat(32),
        timestampMs = 1_800_000_000_000L,
        asset = TxAsset(contract = null, symbol = "ETH", name = "Ether", decimals = 18),
        amount = BigInteger(amount),
        direction = direction,
        fee = null,
        counterparty = "0x" + "b2".repeat(20),
        status = TxStatus.CONFIRMED,
        confirmations = 100,
        inputData = null,
        memo = null,
    )
}
