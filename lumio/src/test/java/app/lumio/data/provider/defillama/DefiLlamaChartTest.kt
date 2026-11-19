package app.lumio.data.provider.defillama

import app.lumio.data.ETH
import app.lumio.data.asset.AssetId
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.price.ChartRange
import app.lumio.data.price.PricePoint
import app.lumio.data.price.PriceSeries
import app.lumio.data.provider.Http
import app.lumio.data.provider.Op
import app.lumio.data.provider.PriceChart
import app.lumio.data.provider.ProviderError
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The DefiLlama chart call against a local MockWebServer. The query is the one the documented `/chart/{coins}` endpoint takes: `start`, `span` and
 * `period`. The answers have the shape of the documented `prices` array. No test reaches a network.
 */
class DefiLlamaChartTest {
    private val server = MockWebServer()
    private val http = Http(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build())

    /** A fixed time in epoch milliseconds: 1_800_000_000 seconds. */
    private val now = 1_800_000_000_000L
    private val usdc = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun cfg() = ProviderConfig(baseUrl = server.url("/").toString())

    private fun chart(asset: AssetId, range: ChartRange): PriceSeries = runBlocking {
        DefiLlamaProvider().execute(PriceChart(asset, range, now), cfg(), http)
    }

    private fun answerFor(coin: String, prices: String) = MockResponse().setBody(
        """{"coins":{"$coin":{"symbol":"X","confidence":0.99,"prices":[$prices]}}}""",
    )

    @Test
    fun theChartAsksTheRangeWindowAndSpacingAndKeepsTheTimeOrder() {
        server.enqueue(
            answerFor(
                "coingecko:ethereum",
                """{"timestamp":1799998800,"price":1840.5},{"timestamp":1799995200,"price":1831.25}""",
            ),
        )

        val series = chart(AssetId.Native(ETH), ChartRange.H24)

        assertEquals(
            listOf(PricePoint(1_799_995_200_000L, 1_831.25), PricePoint(1_799_998_800_000L, 1_840.5)),
            series.points,
        )
        assertEquals("/chart/coingecko:ethereum?start=1799913600&span=25&period=1h", server.takeRequest().path)
    }

    @Test
    fun aRangeOfThirtyDaysAsksDailyPointsFromItsStart() {
        server.enqueue(answerFor("coingecko:ethereum", """{"timestamp":1799998800,"price":1840.5}"""))

        chart(AssetId.Native(ETH), ChartRange.D30)

        assertEquals("/chart/coingecko:ethereum?start=1797408000&span=31&period=1d", server.takeRequest().path)
    }

    @Test
    fun aPointWithNoPositivePriceIsDropped() {
        server.enqueue(
            answerFor(
                "coingecko:ethereum",
                """{"timestamp":1799995200,"price":0},{"timestamp":1799998800,"price":1840.5}""",
            ),
        )

        assertEquals(listOf(PricePoint(1_799_998_800_000L, 1_840.5)), chart(AssetId.Native(ETH), ChartRange.H24).points)
    }

    @Test
    fun aWindowWithNoPricesIsAnEmptySeriesAndNotAFailure() {
        server.enqueue(answerFor("coingecko:ethereum", ""))

        assertEquals(PriceSeries(emptyList()), chart(AssetId.Native(ETH), ChartRange.H24))
    }

    @Test
    fun aCoinTheSourceDoesNotKnowIsUnsupported() {
        server.enqueue(MockResponse().setBody("""{"coins":{}}"""))

        assertFailsWith<ProviderError.Unsupported> { chart(AssetId.Native(ETH), ChartRange.H24) }
    }

    @Test
    fun aTokenIsChartedByItsChainPrefixAndContract() {
        server.enqueue(answerFor("ethereum:$usdc", """{"timestamp":1799998800,"price":1.0}"""))

        val series = chart(AssetId.Erc20(ETH, usdc), ChartRange.D30)

        assertEquals(listOf(PricePoint(1_799_998_800_000L, 1.0)), series.points)
        assertEquals("/chart/ethereum:$usdc?start=1797408000&span=31&period=1d", server.takeRequest().path)
    }

    @Test
    fun aTestnetCoinIsUnsupportedAndNeverCalled() {
        val testnet = NetworkId(Networks.evm(1), testnet = true)

        assertFailsWith<ProviderError.Unsupported> { chart(AssetId.Native(testnet), ChartRange.H24) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theProviderSupportsTheChartOnlyWhereItPricesTheNetwork() {
        val provider = DefiLlamaProvider()
        assertTrue(provider.supports(ETH, Op.PRICE_CHART, cfg()))
        assertFalse(provider.supports(NetworkId(Networks.evm(250), testnet = false), Op.PRICE_CHART, cfg()))
    }
}
