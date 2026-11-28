package app.lumio.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
    fun assetPage() = compose.clearAirSnapshot("asset_page") { EthereumPage(held = held()) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun assetPageLight() = compose.clearAirSnapshot("asset_page_light") { EthereumPage(held = held()) }

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

    /** The frame's activity: one receive of 1 ETH, from a name in the address book, on Base. */
    private fun receive(): TxFeedItem = TxFeedItem(
        tx = LumioTx(
            id = "tx-in",
            network = NetworkId(Networks.evm(8453L), testnet = false),
            account = "0x" + "a1".repeat(20),
            hash = "0x" + "02".repeat(32),
            timestampMs = 1_700_000_000_000L,
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

    /** The frame's chart: 65 prices that fall over the day, the last one a little above the lowest, where the frame puts its diamond. */
    private fun series(): PriceSeries {
        val prices = List(SAMPLE_POINTS) { i -> 4_200.0 + (i % 7) * 12.5 - i * 4.0 }
        val low = prices.min()
        val last = low + LAST_ABOVE_LOW * (prices.max() - low)
        return PriceSeries((prices.dropLast(1) + last).mapIndexed { i, usd -> PricePoint(timeMs = i * HOUR_MS, usd = usd) })
    }

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
            accounts = listOf(CoinAccount(title = "Savings", detail = "Air 1 #0 · 0x1020 3040 … 8191 122b", trailing = "4 ETH", onSend = {}, onReceive = {})),
            onBack = {},
            onLock = {},
            onAllActivity = {},
            activity = {
                ActivityRowItem(item = receive(), names = mapOf(SENDER to "Exchange Deposit"), zone = ZoneOffset.UTC, shown = true, onClick = {}, frame = true)
            },
        )
    }

    private fun chains(): List<ChainHolding> = listOf(
        ChainHolding(
            chain = "Arbitrum",
            network = NetworkId(Networks.evm(42161L), testnet = false),
            total = BigDecimal("13.23"),
            accounts = listOf(
                holding("Arbitrum", "PulsarNova", "Air 1 #5 · 0x1020 3040 … 8191 122b", BigDecimal("10"), chainId = 42161L),
                holding("Arbitrum", "FelixStone", "Air 1 #0 · 0x1020 3040 … 8191 122D", BigDecimal("3.23"), chainId = 42161L),
            ),
        ),
        ChainHolding(
            chain = "Base",
            network = NetworkId(Networks.evm(8453L), testnet = false),
            total = BigDecimal("4.0"),
            accounts = listOf(holding("Base", "Unnamed", "Air 1 #2 · 0x1020 3040 … 8191 127a", BigDecimal("3"), chainId = 8453L)),
        ),
        ChainHolding(
            chain = "Ethereum",
            network = NetworkId(Networks.evm(1L), testnet = false),
            total = BigDecimal("5"),
            accounts = listOf(holding("Ethereum", "PulsarNova", "Air 1 #5 · 0x1020 3040 … 8191 122b", BigDecimal("1"), chainId = 1L)),
        ),
    )

    private fun holding(chain: String, name: String, detail: String, amount: BigDecimal, chainId: Long): AccountHolding = AccountHolding(
        walletId = "wallet",
        accountId = AccountId.of(Networks.evm(chainId), "0x1020304050607080901011121314151617"),
        chain = chain,
        name = name,
        detail = detail,
        amount = amount,
    )

    private companion object {
        const val SAMPLE_POINTS = 65
        const val LAST_ABOVE_LOW = 0.063
        const val HOUR_MS = 3_600_000L
        const val SENDER = "0x9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f"
    }
}
