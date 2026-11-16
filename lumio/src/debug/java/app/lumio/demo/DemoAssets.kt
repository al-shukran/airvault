package app.lumio.demo

import app.lensly.core.btc.BtcNetwork
import app.lumio.btc.networkId
import app.lumio.data.asset.AssetId
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.price.ChartRange
import app.lumio.data.price.Price
import app.lumio.data.price.PricePoint
import app.lumio.data.price.PriceSeries
import app.lumio.home.HomeAsset
import app.lumio.home.NativeAsset
import app.lumio.home.TokenAsset
import java.math.BigDecimal
import kotlin.math.sin

/**
 * Debug-only: the fixed holdings of the demo portfolio, with the prices and charts of its mainnet coins. Names and symbols are the ones the registry
 * and the Bitcoin pages show, so the demo looks as Lumio looks with a real wallet. Testnet coins have no price and no chart, as on a real testnet.
 */
internal object DemoAssets {
    private val ETHEREUM = NetworkId(Family.EVM, "1", false)
    private val BASE = NetworkId(Family.EVM, "8453", false)
    private val MONAD_TESTNET = NetworkId(Family.EVM, "10143", true)
    private val SEPOLIA = NetworkId(Family.EVM, "11155111", true)
    private val BITCOIN = BtcNetwork.MAINNET.networkId()
    private val BITCOIN_TESTNET4 = BtcNetwork.TESTNET4.networkId()

    /** The USDC and USDT contracts of the bundled default token list, lowercase as the list keeps them. */
    const val USDC_ETHEREUM = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    const val USDT_ETHEREUM = "0xdac17f958d2ee523a2206206994597c13d831ec7"
    const val USDC_BASE = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"

    /** Every demo holding, mainnet and testnet. Home shows the side the owner has chosen. */
    val holdings: List<HomeAsset> = listOf(
        NativeAsset(NativeAsset.idOf(BITCOIN), "BTC", "Bitcoin", BITCOIN, BigDecimal("0.08420000")),
        NativeAsset(NativeAsset.idOf(ETHEREUM), "ETH", "Ethereum", ETHEREUM, BigDecimal("1.24660000")),
        NativeAsset(NativeAsset.idOf(BASE), "ETH", "Base", BASE, BigDecimal("0.31200000")),
        TokenAsset(TokenAsset.idOf(ETHEREUM, USDC_ETHEREUM), "USDC", "Ethereum", ETHEREUM, USDC_ETHEREUM, BigDecimal("1850.00")),
        TokenAsset(TokenAsset.idOf(ETHEREUM, USDT_ETHEREUM), "USDT", "Ethereum", ETHEREUM, USDT_ETHEREUM, BigDecimal("420.55")),
        TokenAsset(TokenAsset.idOf(BASE, USDC_BASE), "USDC", "Base", BASE, USDC_BASE, BigDecimal("642.18")),
        NativeAsset(NativeAsset.idOf(MONAD_TESTNET), "MON", "Monad Testnet", MONAD_TESTNET, BigDecimal("12.50000000")),
        NativeAsset(NativeAsset.idOf(BITCOIN_TESTNET4), "BTC", "Bitcoin testnet4", BITCOIN_TESTNET4, BigDecimal("0.00420000")),
        NativeAsset(NativeAsset.idOf(SEPOLIA), "ETH", "Ethereum Sepolia", SEPOLIA, BigDecimal("0.50000000")),
    )

    /**
     * One coin's market: its price in dollars, its change over 24 hours, the start of its longer charts as a change from the price (the 24-hour
     * chart starts from [change24hPct]), and how far its chart wanders, in percent, with [phase] setting where the wander begins.
     */
    private class Quote(
        val asset: AssetId,
        val usd: Double,
        val change24hPct: Double,
        val trendPct: Double,
        val wanderPct: Double,
        val phase: Double,
    )

    private val quotes: Map<AssetId, Quote> = listOf(
        Quote(AssetId.Native(BITCOIN), usd = 96_420.0, change24hPct = 1.82, trendPct = 4.6, wanderPct = 0.9, phase = 0.3),
        Quote(AssetId.Native(ETHEREUM), usd = 3_412.5, change24hPct = -0.94, trendPct = -3.1, wanderPct = 1.3, phase = 1.1),
        Quote(AssetId.Native(BASE), usd = 3_412.5, change24hPct = -0.94, trendPct = -3.1, wanderPct = 1.3, phase = 1.1),
        Quote(AssetId.Erc20(ETHEREUM, USDC_ETHEREUM), usd = 1.0001, change24hPct = 0.01, trendPct = 0.0, wanderPct = 0.02, phase = 0.0),
        Quote(AssetId.Erc20(ETHEREUM, USDT_ETHEREUM), usd = 0.9998, change24hPct = -0.02, trendPct = 0.0, wanderPct = 0.02, phase = 0.5),
        Quote(AssetId.Erc20(BASE, USDC_BASE), usd = 1.0001, change24hPct = 0.01, trendPct = 0.0, wanderPct = 0.02, phase = 0.0),
    ).associateBy { it.asset }

    /** The demo price of [asset] at [nowMs], or null when the demo has none: a testnet coin, or a coin it does not hold. */
    fun price(asset: AssetId, nowMs: Long): Price? =
        quotes[asset]?.let { Price(usd = it.usd, change24hPct = it.change24hPct, asOfMs = nowMs, stale = false) }

    /**
     * The demo chart of [asset] over [range], ending at [nowMs] on the demo price. A testnet coin has an empty chart, since no source prices it. Null when
     * the demo holds no such coin. The last point is the price itself, so the chart and the price agree.
     */
    fun series(asset: AssetId, range: ChartRange, nowMs: Long): PriceSeries? {
        if (asset.net.testnet) return PriceSeries(emptyList())
        val quote = quotes[asset] ?: return null
        val last = range.points - 1
        val start = if (range == ChartRange.H24) 1 / (1 + quote.change24hPct / 100) else 1 + quote.trendPct / 100
        val points = (0..last).map { i ->
            val t = i.toDouble() / last
            val trend = if (i == last) 1.0 else start + (1 - start) * t
            // The wander fades to nothing at the last point, so the chart ends on the price.
            val wander = quote.wanderPct / 100 * sin(i * 0.6 + quote.phase) * (1 - t)
            PricePoint(timeMs = nowMs - (last - i) * range.periodMs, usd = quote.usd * trend * (1 + wander))
        }
        return PriceSeries(points)
    }
}
