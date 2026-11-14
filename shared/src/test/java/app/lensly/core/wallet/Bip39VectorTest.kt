package app.lensly.core.wallet

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.utils.Numeric

/**
 * BIP39 mnemonic and seed vectors from trezor/python-mnemonic `vectors.json`.
 *
 * Only the 24-word entries are checked: [Mnemonic.toSeed] requires exactly 24 valid English
 * words, so the upstream 12-word entries are refused by production policy and are not in the
 * fixture. The passphrase is the upstream `TREZOR` one.
 */
class Bip39VectorTest {

    private val vectors = PublishedWalletVectors.bip39()

    @Test
    fun entropyEncodesToPublishedWords() {
        assertEquals(8, vectors.size)
        for (vector in vectors) {
            assertEquals(
                vector.mnemonic,
                Mnemonic.fromEntropy(Numeric.hexStringToByteArray(vector.entropy)),
                "words for entropy ${vector.entropy}",
            )
        }
    }

    @Test
    fun publishedWordsAreAccepted() {
        for (vector in vectors) {
            assertTrue(Mnemonic.isValid(vector.mnemonic), "vector ${vector.mnemonic.first()} ... ${vector.mnemonic.last()}")
        }
    }

    @Test
    fun seedMatchesPublishedTrezorSeed() {
        for (vector in vectors) {
            val seed = Mnemonic.toSeed(vector.mnemonic, vector.passphrase)
            try {
                assertEquals(
                    vector.seed,
                    seed.toHex(),
                    "seed for entropy ${vector.entropy} with passphrase ${vector.passphrase}",
                )
            } finally {
                seed.fill(0)
            }
        }
    }

    @Test
    fun fixtureRowsAreUnique() {
        // A duplicate row would let one wrong seed hide behind another, so the eight entropy values
        // and the eight seeds must all differ.
        assertEquals(vectors.size, vectors.map { it.entropy }.toSet().size)
        assertEquals(vectors.size, vectors.map { it.seed }.toSet().size)
    }

    @Test
    fun twentyFourWordPolicyIsUnchanged() {
        // The upstream file also publishes 12-word mnemonics for the same entropy. Lensly refuses them.
        assertFailsWith<IllegalArgumentException> {
            Mnemonic.toSeed(vectors.first().mnemonic.take(12), vectors.first().passphrase)
        }
    }
}
