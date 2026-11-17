package app.lensly.app

import fr.acinq.bitcoin.Base58
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The bundled token list, read through the assets as the app reads it. */
@RunWith(RobolectricTestRunner::class)
class SolanaTokensHolderTest {

    private val tokens by lazy { SolanaTokensHolder.get(RuntimeEnvironment.getApplication()) }

    @Test
    fun theBundledListNamesUsdcAndUsdtAtSixDecimals() {
        assertEquals(6, assertNotNull(tokens.token(USDC)).decimals)
        assertEquals(6, assertNotNull(tokens.token(USDT)).decimals)
    }

    @Test
    fun everyListedMintIsA32ByteAddressWithANameAndASymbol() {
        assertTrue(tokens.tokens.size >= 50, "the list has ${tokens.tokens.size} tokens")
        tokens.tokens.forEach { token ->
            assertEquals(32, Base58.decode(token.mint).size, "the mint of ${token.symbol}")
            assertTrue(token.symbol.isNotBlank() && token.name.isNotBlank(), "${token.mint} has no name or symbol")
        }
    }

    private companion object {
        /** The USDC mint on Solana. */
        const val USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

        /** The USDT (Tether) mint on Solana, as CoinGecko lists it and the chain confirms it. */
        const val USDT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
    }
}
