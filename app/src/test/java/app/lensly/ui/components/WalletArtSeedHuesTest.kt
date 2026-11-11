package app.lensly.ui.components

import app.lensly.app.ProfileColour
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wallets drawn before the palette doubled keep their art: a seed picks its glow hues from the first eight colours, as it
 * always did. This freezes the original palette and its SplitMix64 picks and checks that a wallet with no colour still gets
 * the same three hues for a sample of seeds.
 */
class WalletArtSeedHuesTest {
    private val originalPalette = listOf(
        ProfileColour.RED,
        ProfileColour.ORANGE,
        ProfileColour.YELLOW,
        ProfileColour.GREEN,
        ProfileColour.TEAL,
        ProfileColour.BLUE,
        ProfileColour.VIOLET,
        ProfileColour.PINK,
    )

    @Test
    fun `a seed with no colour picks the hues of the original eight-colour palette`() {
        for (index in 0 until 20) {
            val seed = "wallet-$index"
            assertEquals("seed $seed", originalHues(seed), seedHues(ArtRandom(seed), colour = null))
        }
    }

    /** The hues the art picked before the palette grew, recomputed from the original eight colours. */
    private fun originalHues(seed: String): List<ProfileColour> {
        var state = seed.hashCode().toLong()
        fun nextBits(): Long {
            state += GOLDEN_GAMMA
            var z = state
            z = (z xor (z ushr 30)) * MIX_ONE
            z = (z xor (z ushr 27)) * MIX_TWO
            return z xor (z ushr 31)
        }
        fun pick(): ProfileColour = originalPalette[((nextBits() ushr 33) % originalPalette.size).toInt()]
        return listOf(pick(), pick(), pick())
    }

    private companion object {
        // SplitMix64 constants, as signed Longs: the same values the art has always used.
        const val GOLDEN_GAMMA = -0x61c8864680b583ebL
        const val MIX_ONE = -0x40a7b892e31b1a47L
        const val MIX_TWO = -0x6b2fb644ecceee15L
    }
}
