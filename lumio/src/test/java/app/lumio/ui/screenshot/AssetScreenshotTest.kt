package app.lumio.ui.screenshot

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.airvault.core.model.AccountId
import app.lumio.data.net.Networks
import app.lumio.data.price.ChartRange
import app.lumio.data.price.PricePoint
import app.lumio.data.price.PriceSeries
import app.lumio.ui.asset.AccountHolding
import app.lumio.ui.asset.AssetPage
import app.lumio.ui.asset.ChainHolding
import app.lumio.ui.asset.CoinHeld
import app.lumio.ui.asset.HoldingsPage
import app.lumio.ui.asset.PriceChange
import java.math.BigDecimal
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Asset page (C4) and Holdings Breakdown (C5) as the design draws them: Ethereum held on three chains across five accounts. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
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

    private fun held(): CoinHeld = CoinHeld(amount = "22.23 ETH", summary = "5 accounts · 3 chains", value = "$95,271.11")

    private fun series(): PriceSeries = PriceSeries(List(SAMPLE_POINTS) { i -> PricePoint(timeMs = i * HOUR_MS, usd = 4_200.0 + (i % 7) * 12.5 - i * 4.0) })

    @Composable
    private fun EthereumPage(held: CoinHeld?) {
        AssetPage(
            name = "Ethereum",
            symbol = "ETH",
            icon = { Spacer(Modifier.size(ICON_SIZE)) },
            priceText = "$4,285.70",
            change = PriceChange(text = "▼ 2.41% · −$105.82 Today", rising = false),
            series = series(),
            range = ChartRange.D1,
            onRange = {},
            watched = true,
            onWatch = {},
            held = held,
            onHoldings = {},
            onSend = {},
            onReceive = {},
            onBack = {},
            onLock = {},
            onAllActivity = {},
            activity = { Text(text = "No activity yet. Your first receive will appear here.") },
        )
    }

    private fun chains(): List<ChainHolding> = listOf(
        ChainHolding(
            chain = "Arbitrum",
            total = BigDecimal("13.23"),
            accounts = listOf(
                holding("Arbitrum", "PulsarNova", "Air 1 #5 · 0x1020…122b", BigDecimal("10"), chainId = 42161L),
                holding("Arbitrum", "FelixStone", "Air 1 #0 · 0x1020…122D", BigDecimal("3.23"), chainId = 42161L),
            ),
        ),
        ChainHolding(
            chain = "Base",
            total = BigDecimal("4.0"),
            accounts = listOf(holding("Base", "Unnamed", "Air 1 #2 · 0x1020…127a", BigDecimal("3"), chainId = 8453L)),
        ),
        ChainHolding(
            chain = "Ethereum",
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
        amount = amount,
    )

    private companion object {
        const val SAMPLE_POINTS = 25
        const val HOUR_MS = 3_600_000L
        val ICON_SIZE = 48.dp
    }
}
