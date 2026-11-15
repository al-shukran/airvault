package app.lumio.evm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

/** The cache merge: a fresh read replaces its own key, a read of an address no longer shown is dropped, and other wallets are kept. */
class PortfolioMergeTest {

    private fun read(wallet: String, address: String, wei: String, atMs: Long) =
        CachedBalance(walletId = wallet, chainId = 1L, address = address, wei = wei, nonce = 0L, atMs = atMs)

    @Test
    fun aFreshReadReplacesTheOldOneForTheSameAddressAndChain() {
        val old = read("w1", "0xA", "1", atMs = 10L)
        val fresh = read("w1", "0xA", "2", atMs = 20L)

        val merged = Portfolio.merged(listOf(old), "w1", shown = setOf("0xA"), fresh = listOf(fresh))

        assertEquals(listOf(fresh), merged)
    }

    @Test
    fun anAddressNoLongerShownIsDroppedAndOtherWalletsAreKept() {
        val shownOld = read("w1", "0xA", "1", atMs = 10L)
        val hiddenOld = read("w1", "0xB", "1", atMs = 10L)
        val otherWallet = read("w2", "0xB", "7", atMs = 10L)

        val merged = Portfolio.merged(listOf(shownOld, hiddenOld, otherWallet), "w1", shown = setOf("0xA"), fresh = emptyList())

        assertEquals(listOf(shownOld, otherWallet), merged)
    }

    @Test
    fun theBalanceOfAShownAddressSurvivesAnOfflineRefresh() {
        val old = read("w1", "0xA", "5", atMs = 10L)

        val merged = Portfolio.merged(listOf(old), "w1", shown = setOf("0xA"), fresh = emptyList())

        assertEquals(BigInteger.valueOf(5), BigInteger(merged.single().wei))
        assertEquals(10L, merged.single().atMs)
    }
}
