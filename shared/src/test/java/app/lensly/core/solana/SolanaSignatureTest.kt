package app.lensly.core.solana

import app.lensly.core.wallet.Ed25519Signer
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SolanaSignatureTest {

    /**
     * Ed25519 is deterministic, so the signature solders makes for a seed and a message is the one Lensly must make, byte for byte. The
     * signature also verifies with the public key, and it fails for a message that differs in one byte.
     */
    @Test
    fun theSignatureOfAMessageMatchesSoldersAndVerifiesWithThePublicKey() {
        val seed = SolanaVectors["ed25519-seed"]
        val publicKey = SolanaVectors["ed25519-pubkey"]
        val message = SolanaVectors["spl-compute-transfer-checked"]
        val signature = SolanaVectors["ed25519-signature-spl-compute-transfer-checked"]

        assertContentEquals(publicKey, Ed25519Signer.publicKeyOf(seed))
        assertContentEquals(signature, Ed25519Signer.sign(seed, message))
        assertTrue(Ed25519Signer.verify(publicKey, message, signature))

        val changed = message.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFalse(Ed25519Signer.verify(publicKey, changed, signature))
    }
}
