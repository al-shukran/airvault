package app.lumio.evm

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The totals keep mainnet and testnet apart, and sum only the same asset on the same side. */
class PortfolioTotalsTest {

    private val ethereum = chain(1L, "Ethereum", "ETH", testnet = false)
    private val base = chain(8453L, "Base", "ETH", testnet = false)
    private val sepolia = chain(11155111L, "Sepolia", "ETH", testnet = true)
    private val monadTestnet = chain(10143L, "Monad Testnet", "MON", testnet = true)

    private fun chain(id: Long, name: String, symbol: String, testnet: Boolean) =
        EvmChain(id, name, symbol, 18, testnet, explorerUrl = null)

    private fun holding(chain: EvmChain, wei: String, atMs: Long) = Holding(chain, BigInteger(wei), atMs)

    @Test
    fun aTestnetBalanceIsNeverAddedToAMainnetTotal() {
        val totals = PortfolioTotals.of(
            listOf(holding(ethereum, "1000000000000000000", 100L), holding(sepolia, "500000000000000000", 200L)),
        )

        assertEquals(listOf(Total("ETH", 18, BigInteger("1000000000000000000"), 1, testnet = false)), totals.mainnet)
        assertEquals(listOf(Total("ETH", 18, BigInteger("500000000000000000"), 1, testnet = true)), totals.testnet)
    }

    @Test
    fun theSameAssetOnSeveralMainnetsIsSummedAndCountsItsNetworks() {
        val totals = PortfolioTotals.of(
            listOf(holding(ethereum, "1000000000000000000", 1L), holding(base, "2000000000000000000", 1L)),
        )

        assertEquals(listOf(Total("ETH", 18, BigInteger("3000000000000000000"), 2, testnet = false)), totals.mainnet)
        assertEquals(emptyList<Total>(), totals.testnet)
    }

    @Test
    fun eachSideListsItsAssetsBySymbol() {
        val totals = PortfolioTotals.of(
            listOf(holding(sepolia, "1", 1L), holding(monadTestnet, "2", 1L)),
        )

        assertEquals(listOf("ETH", "MON"), totals.testnet.map { it.symbol })
    }

    @Test
    fun theOldestReadIsTheEarliestHolding() {
        val totals = PortfolioTotals.of(listOf(holding(ethereum, "1", 300L), holding(sepolia, "1", 120L)))

        assertEquals(120L, totals.oldestMs)
    }

    @Test
    fun noHoldingsGiveNoTotalsAndNoReadTime() {
        val totals = PortfolioTotals.of(emptyList())

        assertEquals(emptyList<Total>(), totals.mainnet)
        assertEquals(emptyList<Total>(), totals.testnet)
        assertNull(totals.oldestMs)
    }
}
