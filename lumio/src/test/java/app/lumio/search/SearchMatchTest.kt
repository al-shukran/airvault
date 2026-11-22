package app.lumio.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatchTest {
    private val usdCoin = SearchEntry("USD Coin", "USDC · on 9 chains", listOf("USD Coin", "USDC"), "usdc")
    private val solana = SearchEntry("Solana", "SOL · mainnet", listOf("Solana", "SOL"), "sol")

    @Test
    fun aBlankQueryFindsNothing() {
        assertEquals(emptyList<SearchEntry<String>>(), matching("   ", listOf(usdCoin, solana)))
    }

    @Test
    fun aQueryMatchesNameOrTickerWithoutCaseAndIgnoresSurroundingSpaces() {
        assertEquals(listOf(usdCoin), matching(" usd ", listOf(usdCoin, solana)))
        assertEquals(listOf(solana), matching("SOL", listOf(usdCoin, solana)))
    }

    @Test
    fun anEvmAddressIsAnAddress() {
        assertTrue(looksLikeAddress("0x1020304050607080901020304050607080901122"))
    }

    @Test
    fun aBitcoinOrSolanaOrAlgorandAddressIsAnAddress() {
        assertTrue(looksLikeAddress("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"))
        assertTrue(looksLikeAddress("11111111111111111111111111111111"))
        assertTrue(looksLikeAddress("A".repeat(58)))
    }

    @Test
    fun aWordOrATickerIsNotAnAddress() {
        assertFalse(looksLikeAddress("usd"))
        assertFalse(looksLikeAddress("0x1234"))
        assertFalse(looksLikeAddress("Solana"))
    }
}
