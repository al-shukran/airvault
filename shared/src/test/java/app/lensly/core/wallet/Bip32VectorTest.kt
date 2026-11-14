package app.lensly.core.wallet

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric

/**
 * BIP32 test vectors 1 and 2 from bitcoin/bips `bip-0032.mediawiki`.
 *
 * [HdWallet] only exposes the master fingerprint and the fixed `m/44'/60'/0'` account, so the
 * published sub-paths (`m/0H`, `m/0H/1`, ...) cannot be observed here. The fingerprint still
 * pins the seed-to-master-key step to published data; the derivation below it is covered by the
 * viem vectors in [HdWalletTest].
 */
class Bip32VectorTest {

    private val vectors = PublishedWalletVectors.bip32()

    @Test
    fun fixtureFingerprintIsHash160OfPublishedMasterKey() {
        assertEquals(2, vectors.size)
        for (vector in vectors) {
            val publicKey = Numeric.hexStringToByteArray(vector.masterPublicKey)
            assertEquals(33, publicKey.size, "compressed key of vector ${vector.vector}")
            assertEquals(
                vector.masterFingerprint,
                Hash.sha256hash160(publicKey).copyOfRange(0, 4).toHex(),
                "fingerprint of the xpub in vector ${vector.vector}",
            )
        }
    }

    @Test
    fun masterFingerprintMatchesPublishedVector() {
        for (vector in vectors) {
            val wallet = HdWallet(Numeric.hexStringToByteArray(vector.seed))
            assertEquals(
                vector.masterFingerprint,
                wallet.masterFingerprintHex,
                "master fingerprint of vector ${vector.vector}",
            )
        }
    }

    @Test
    fun vectorSeedsAreTheEndsOfTheAcceptedSeedRange() {
        // Vector 1 seeds 16 bytes, the smallest HdWallet accepts; vector 2 seeds 64 bytes, the largest.
        assertEquals(listOf(16, 64), vectors.map { Numeric.hexStringToByteArray(it.seed).size })
        assertFailsWith<IllegalArgumentException> { HdWallet(ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { HdWallet(ByteArray(65)) }
    }

    @Test
    fun masterXpubsAreDistinct() {
        // Guards against a copy-paste that duplicates one published xpub into both rows.
        assertEquals(vectors.size, vectors.map { it.masterXpub }.toSet().size)
    }
}
