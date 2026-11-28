package app.lumio.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.airvault.core.model.AccountId
import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.price.ChartFrame
import app.lumio.data.price.PricePoint
import app.lumio.data.price.PriceSeries
import app.lumio.ui.asset.AccountHolding
import app.lumio.ui.asset.AssetPage
import app.lumio.ui.asset.ChainHolding
import app.lumio.ui.asset.CoinAccount
import app.lumio.ui.asset.CoinHeld
import app.lumio.ui.asset.HoldingsPage
import app.lumio.ui.asset.PriceChange
import app.lumio.ui.home.AssetIcon
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.transactions.ActivityRowItem
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.transactions.TxFeedItem
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Asset page (C4) and Holdings Breakdown (C5) as the design draws them: Ethereum held on three chains across five accounts. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssetScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun assetPage() = compose.clearAirSnapshot("asset_page", frame = FRAME) { EthereumPage(held = held()) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun assetPageLight() = compose.clearAirSnapshot("asset_page_light", frame = FRAME) { EthereumPage(held = held()) }

    @Test
    fun assetPageNotHeld() = compose.clearAirSnapshot("asset_page_not_held") { EthereumPage(held = null) }

    @Test
    fun holdingsBreakdown() = compose.clearAirSnapshot("asset_holdings") {
        HoldingsPage(
            title = "Your ETH",
            symbol = "ETH",
            chains = chains(),
            showBalance = true,
            onAccount = {},
            onBack = {},
            onLock = {},
        )
    }

    private val registry: Registry = RegistryLoader.load { path ->
        listOf("../shared/src/main/assets", "../shared/src/debug/assets")
            .map { File(it, path) }
            .firstOrNull(File::isFile)
            ?.readText()
    }

    private fun held(): CoinHeld = CoinHeld(
        amount = "22.23 ETH",
        summary = "5 accounts · 3 chains",
        value = "$95,271.11",
        chainIcons = listOf(42161L, 8453L, 1L).map { AssetIconSpec.Chain(registry.network(it)) },
    )

    /** The frame's activity: one receive of 1 ETH, from a name in the address book, on Base, 2 hours ago. */
    private fun receive(): TxFeedItem = TxFeedItem(
        tx = LumioTx(
            id = "tx-in",
            network = NetworkId(Networks.evm(8453L), testnet = false),
            account = "0x" + "a1".repeat(20),
            hash = "0x" + "02".repeat(32),
            timestampMs = RECEIVED_MS,
            asset = TxAsset(contract = null, symbol = "ETH", name = "Ether", decimals = 18),
            amount = BigInteger("1000000000000000000"),
            direction = TxDirection.IN,
            fee = null,
            counterparty = SENDER,
            status = TxStatus.CONFIRMED,
            confirmations = 120L,
            inputData = null,
            memo = null,
        ),
        usd = 4285.7,
        chain = "Base",
    )

    /**
     * The frame's chart: 65 prices whose ticks, each from one price to the next, come nearest the frame's 64 ticks. A price is [CHART_TOP] less its
     * height in dp under the top of the frame's 120 dp chart box, so the highest one sits at 6 dp and the lowest at 114 dp, as the frame's do, and the
     * last one, where the diamond sits, at 67.6 dp.
     */
    private fun series(): PriceSeries = PriceSeries(FRAME_CHART.mapIndexed { i, y -> PricePoint(timeMs = i * HOUR_MS, usd = CHART_TOP - y) })

    @Composable
    private fun EthereumPage(held: CoinHeld?) {
        AssetPage(
            name = "Ethereum",
            symbol = "ETH",
            icon = { size -> AssetIcon(spec = AssetIconSpec.Chain(registry.network(1L)), size = size) },
            priceText = "$4,285.70",
            change = PriceChange(text = "▼ 2.41% · −$105.82 Today", rising = false),
            series = series(),
            range = ChartFrame.D1,
            onRange = {},
            watched = true,
            onWatch = {},
            held = held,
            onHoldings = {},
            accounts = listOf(CoinAccount(title = "Savings", detail = "Air 1 #0", address = "0x1020304050607080901011121314151617181920", trailing = "4 ETH", onSend = {}, onReceive = {})),
            onBack = {},
            onLock = {},
            onAllActivity = {},
            activity = {
                ActivityRowItem(item = receive(), names = mapOf(SENDER to "Exchange Deposit"), zone = ZoneOffset.UTC, shown = true, onClick = {}, frame = true, nowMs = RECEIVED_MS + 2 * HOUR_MS)
            },
        )
    }

    private fun chains(): List<ChainHolding> = listOf(
        ChainHolding(
            chain = "Arbitrum",
            network = NetworkId(Networks.evm(42161L), testnet = false),
            total = BigDecimal("13.23"),
            accounts = listOf(
                holding("Arbitrum", "PulsarNova", "Air 1 #5 · 0x1020…122b", BigDecimal("10"), chainId = 42161L),
                holding("Arbitrum", "FelixStone", "Air 1 #0 · 0x1020…122D", BigDecimal("3.23"), chainId = 42161L),
            ),
        ),
        ChainHolding(
            chain = "Base",
            network = NetworkId(Networks.evm(8453L), testnet = false),
            total = BigDecimal("4.0"),
            accounts = listOf(holding("Base", "Unnamed", "Air 1 #2 · 0x1020…127a", BigDecimal("3"), chainId = 8453L)),
        ),
        ChainHolding(
            chain = "Ethereum",
            network = NetworkId(Networks.evm(1L), testnet = false),
            total = BigDecimal("5"),
            accounts = listOf(holding("Ethereum", "PulsarNova", "Air 1 #5 · 0x1020…122b", BigDecimal("1"), chainId = 1L)),
        ),
    )

    private fun holding(chain: String, name: String, detail: String, amount: BigDecimal, chainId: Long): AccountHolding = AccountHolding(
        walletId = "wallet",
        accountId = AccountId.of(Networks.evm(chainId), "0x1020304050607080901011121314151617"),
        chain = chain,
        name = name,
        detail = detail,
        address = "0x1020304050607080901011121314151617181920",
        amount = amount,
    )

    private companion object {
        val FRAME = DpSize(360.dp, 740.dp)
        const val CHART_TOP = 4_400.0
        val FRAME_CHART = listOf(
            60.6, 76.8, 57.7, 83.7, 74.8, 49.8, 80.6, 71.5, 87.9, 64.4, 114.0, 93.8, 79.6, 66.0, 90.0, 80.0, 89.8, 71.2, 88.3, 66.0, 79.8, 57.2,
            72.7, 63.5, 81.8, 52.9, 71.4, 52.8, 76.6, 54.6, 82.5, 69.3, 83.0, 70.3, 53.3, 76.4, 44.0, 64.1, 21.6, 56.1, 36.2, 15.1, 36.2, 6.0,
            20.4, 42.2, 32.7, 28.3, 45.6, 31.5, 11.9, 37.8, 11.1, 46.2, 24.8, 71.8, 41.4, 65.7, 81.8, 48.4, 57.9, 80.3, 60.7, 52.9, 67.6,
        )
        const val HOUR_MS = 3_600_000L
        const val RECEIVED_MS = 1_700_000_000_000L
        const val SENDER = "0x9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f"
    }
}
