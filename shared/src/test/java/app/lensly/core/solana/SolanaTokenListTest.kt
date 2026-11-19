package app.lensly.core.solana

import app.airvault.chains.solana.signer.SolanaToken
import app.airvault.chains.solana.signer.SolanaTokenList
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import org.junit.Test

class SolanaTokenListTest {

    private val usdc = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private val wrappedSol = "So11111111111111111111111111111111111111112"

    private fun file(vararg rows: String): String = """{"source": "test", "tokens": [${rows.joinToString(",")}]}"""

    private fun row(mint: String, symbol: String, name: String, decimals: Int) =
        """{"mint": "$mint", "symbol": "$symbol", "name": "$name", "decimals": $decimals, "coingeckoId": "x"}"""

    @Test
    fun aListedMintNamesItsTokenAndAnUnlistedMintNamesNothing() {
        val list = SolanaTokenList.parse(
            file(row(usdc, "USDC", "USD Coin", 6), row(wrappedSol, "SOL", "Wrapped SOL", 9)),
        )

        assertEquals(SolanaToken(usdc, "USDC", "USD Coin", 6), list.token(usdc))
        assertEquals(SolanaToken(wrappedSol, "SOL", "Wrapped SOL", 9), list.token(wrappedSol))
        assertNull(list.token(SolanaVectors.MINT22))
    }

    @Test
    fun theEmptyListNamesNoMint() {
        assertNull(SolanaTokenList.EMPTY.token(usdc))
    }

    @Test
    fun aMintListedTwiceIsRefused() {
        assertFails { SolanaTokenList.parse(file(row(usdc, "USDC", "USD Coin", 6), row(usdc, "USDC", "USD Coin", 6))) }
    }

    @Test
    fun decimalsOutsideZeroToEighteenAreRefused() {
        assertFails { SolanaTokenList.parse(file(row(usdc, "USDC", "USD Coin", 19))) }
        assertFails { SolanaTokenList.parse(file(row(usdc, "USDC", "USD Coin", -1))) }
        assertEquals(18, SolanaTokenList.parse(file(row(usdc, "USDC", "USD Coin", 18))).token(usdc)?.decimals)
    }

    @Test
    fun malformedJsonIsRefused() {
        assertFails { SolanaTokenList.parse("{") }
        assertFails { SolanaTokenList.parse("""{"tokens": [{"mint": "$usdc", "symbol": "USDC"}]}""") }
    }
}
