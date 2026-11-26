package app.lumio.portfolio

import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.price.PRICE_TTL_MS
import app.lumio.data.price.Price
import app.lumio.home.HomeAssets
import app.lumio.home.NativeAsset
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortfolioStaleTest {
    private val now = Instant.parse("2026-11-26T15:30:00Z").toEpochMilli()
    private val ethereum = NetworkId(Networks.evm(1), testnet = false)
    private val base = NetworkId(Networks.evm(8453), testnet = false)

    private fun price(asOfMs: Long, stale: Boolean) = Price(usd = 1.0, change24hPct = null, asOfMs = asOfMs, stale = stale)

    @Test
    fun aFreshPriceIsNeverMarkedHoweverOldItsSourceTimeIs() {
        assertNull(HomeAssets.staleAsOfMs(price(now - 10 * PRICE_TTL_MS, stale = false), now))
    }

    @Test
    fun aLastPriceOlderThanTheRefreshWindowIsMarkedWithItsTime() {
        val asOf = now - PRICE_TTL_MS - 1
        assertEquals(asOf, HomeAssets.staleAsOfMs(price(asOf, stale = true), now))
    }

    @Test
    fun aLastPriceWithinTheRefreshWindowReadsAsLive() {
        assertNull(HomeAssets.staleAsOfMs(price(now - PRICE_TTL_MS, stale = true), now))
    }

    @Test
    fun noPriceHasNoMark() {
        assertNull(HomeAssets.staleAsOfMs(null, now))
    }

    @Test
    fun aRowTakesTheOldestMarkOfItsHoldingsAndStaysFreshWhenNoneIsMarked() {
        fun eth(network: NetworkId, id: String, staleAsOfMs: Long?) =
            holdingOf(NativeAsset(id, "ETH", "x", network, BigDecimal.ONE, usd = 10.0), staleAsOfMs = staleAsOfMs)

        val marked = PortfolioView.rows(listOf(eth(ethereum, "e1", 500L), eth(base, "e2", 300L)), PortfolioFilter()).single()
        val fresh = PortfolioView.rows(listOf(eth(ethereum, "e1", null), eth(base, "e2", null)), PortfolioFilter()).single()

        assertEquals(300L, marked.staleAsOfMs)
        assertNull(fresh.staleAsOfMs)
    }

    @Test
    fun aMarkedRowKeepsItsLastKnownChange() {
        val holding = holdingOf(NativeAsset("b1", "BTC", "x", ethereum, BigDecimal.ONE, usd = 10.0, change24h = 1.82), staleAsOfMs = 500L)

        val row = PortfolioView.rows(listOf(holding), PortfolioFilter()).single()

        assertEquals(500L, row.staleAsOfMs)
        assertEquals(1.82, row.change24h!!, 0.0)
    }

    @Test
    fun theCaptionShowsTheClockToday() {
        val at = Instant.parse("2026-11-26T09:05:00Z").toEpochMilli()
        assertEquals("as of 09:05", PortfolioFormat.asOf(at, now, ZoneOffset.UTC))
    }

    @Test
    fun theCaptionShowsTheDayWhenNotToday() {
        val at = Instant.parse("2026-11-25T22:10:00Z").toEpochMilli()
        assertEquals("as of 25 Nov", PortfolioFormat.asOf(at, now, ZoneOffset.UTC))
    }
}
