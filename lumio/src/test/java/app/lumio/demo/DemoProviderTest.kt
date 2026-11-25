package app.lumio.demo

import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.GasFees
import app.lumio.data.provider.Http
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.NativePrice
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.BitcoinMarket
import java.io.IOException
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The demo provider answers with the values of the mockups, and with the fault that its profile asks for. */
class DemoProviderTest {
    private val http = Http(OkHttpClient())
    private val ethereum = NetworkId(Networks.evm(1), testnet = false)
    private val monad = NetworkId(Networks.evm(10143), testnet = true)
    private fun provider(profile: DemoProfile) = DemoProvider { profile }
    private fun <T> run(profile: DemoProfile, req: app.lumio.data.provider.Request<T>): T =
        runBlocking { provider(profile).execute(req, ProviderConfig(), http) }

    @Test
    fun pulsarNovaHoldsElevenEthOnMainnetAndTwelveAndAHalfMonOnTestnet() {
        val address = DemoFixtures.evmAddresses[0]
        assertEquals(BigInteger.TEN.pow(18) * BigInteger.valueOf(11), run(DemoProfile.FULL, NativeBalance(ethereum, address)))
        assertEquals(BigInteger.TEN.pow(17) * BigInteger.valueOf(125), run(DemoProfile.FULL, NativeBalance(monad, address)))
    }

    @Test
    fun theEmptyProfileHoldsNothingOnMainnetButKeepsTheTestFunds() {
        val address = DemoFixtures.evmAddresses[0]
        assertEquals(BigInteger.ZERO, run(DemoProfile.EMPTY, NativeBalance(ethereum, address)))
        assertTrue(run(DemoProfile.EMPTY, NativeBalance(monad, address)).signum() > 0)
    }

    @Test
    fun pricesAndMarketAreTheMockupValues() {
        assertEquals(DemoFixtures.ETH_USD, run(DemoProfile.FULL, NativePrice(ethereum)).usd, 0.0)
        assertEquals(3.41e12, run(DemoProfile.FULL, BitcoinMarket()).capUsd, 0.0)
    }

    @Test
    fun theFeeQuoteGivesAMaxFeeOfAboutThreeAndAHalfThousandthsOfAMon() {
        val quote = run(DemoProfile.FULL, GasFees(monad))
        val maxFeePerGas = quote.baseFee * BigInteger.TWO + quote.priorityFee
        assertEquals(BigInteger.valueOf(3_399_900_000_000_000L), maxFeePerGas * BigInteger.valueOf(21_000))
    }

    @Test
    fun theFaultProfilesFail() {
        assertThrows(IOException::class.java) { run(DemoProfile.OFFLINE, NativePrice(ethereum)) }
        assertThrows(ProviderError.Fatal::class.java) { run(DemoProfile.ERROR, NativePrice(ethereum)) }
    }
}
