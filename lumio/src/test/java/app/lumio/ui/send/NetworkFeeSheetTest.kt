package app.lumio.ui.send

import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lumio.evm.EvmAdvanced
import app.lumio.evm.EvmChain
import app.lumio.evm.PreparedSend
import app.lumio.pair.PairFixtures
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The fee stops of the EVM send: Slow halves the suggested priority fee and Fast doubles it, and the fee cap moves by the same amount, so the stop
 * reads back from the fields it sets.
 */
class NetworkFeeSheetTest {

    private val chain = EvmChain(10143L, "Monad Testnet", "MON", 18, true, "https://testnet.monadexplorer.com")

    /** A suggested send with a fee cap of 100 gwei and a priority fee of 2 gwei. */
    private val suggested = PreparedSend(
        chain = chain,
        index = 0,
        from = AccountAddresses.addressAt(HdWallet(PairFixtures.seed).accountXpub(), 0),
        to = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        valueWei = BigInteger.TEN.pow(16),
        nonce = 7,
        gasLimit = BigInteger.valueOf(21_000),
        maxFeePerGas = gwei(100),
        maxPriorityFeePerGas = gwei(2),
        balanceWei = BigInteger.TEN.pow(18),
    )

    @Test
    fun normalIsTheSuggestedFeesAsTheSheetShowsThem() {
        val fields = fieldsFor(suggested, FeeStop.Normal)

        assertEquals(EvmAdvanced.fieldsOf(suggested), fields)
    }

    @Test
    fun slowHalvesThePriorityFeeAndLowersTheCapByTheSameAmount() {
        val fields = fieldsFor(suggested, FeeStop.Slow)

        assertEquals("99", fields.maxFeeGwei)
        assertEquals("1", fields.maxPriorityGwei)
    }

    @Test
    fun fastDoublesThePriorityFeeAndRaisesTheCapByTheSameAmount() {
        val fields = fieldsFor(suggested, FeeStop.Fast)

        assertEquals("102", fields.maxFeeGwei)
        assertEquals("4", fields.maxPriorityGwei)
    }

    @Test
    fun eachStopReadsBackFromItsFields() {
        FeeStop.entries.forEach { stop ->
            assertEquals(stop, stopOf(suggested, fieldsFor(suggested, stop)))
        }
    }

    @Test
    fun aValueBetweenTheStopsReadsAsTheNearestOne() {
        val nearSlow = EvmAdvanced.fieldsOf(suggested).copy(maxPriorityGwei = "1.2")
        val nearFast = EvmAdvanced.fieldsOf(suggested).copy(maxPriorityGwei = "3.5")

        assertEquals(FeeStop.Slow, stopOf(suggested, nearSlow))
        assertEquals(FeeStop.Fast, stopOf(suggested, nearFast))
    }

    @Test
    fun aPriorityFeeThatIsNotANumberReadsAsNormal() {
        val typing = EvmAdvanced.fieldsOf(suggested).copy(maxPriorityGwei = "2.")

        assertEquals(FeeStop.Normal, stopOf(suggested, typing))
    }

    private fun gwei(value: Long): BigInteger = BigInteger.valueOf(value).multiply(BigInteger.TEN.pow(9))
}
