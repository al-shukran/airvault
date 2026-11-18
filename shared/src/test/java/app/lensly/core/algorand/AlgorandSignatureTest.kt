package app.lensly.core.algorand

import app.airvault.chains.algorand.bytesToSign
import app.airvault.chains.algorand.signer.AlgoTxnDecoder
import app.lensly.core.wallet.Ed25519Signer
import app.lensly.core.wallet.Mnemonic
import app.lensly.core.wallet.toHex
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The signature of an Algorand transaction. The key is the WI-11 key of account N under the 24-word phrase of 23 "abandon" and "art"
 * (plan section 4.4). The signed bytes are "TX" followed by the SDK payment `pay_basic` from the decoder fixtures.
 */
class AlgorandSignatureTest {

    @Test
    fun theSignatureOfTheSdkPaymentVerifiesWithTheAccountZeroPublicKey() {
        val payment = AlgoTxnVectors.valid().single { it.name == "pay_basic" }
        val signed = bytesToSign(payment.raw)
        assertContentEquals(payment.bytesToSign, signed)

        val key = LedgerEd25519.algorandAccount(seed(), 0)
        try {
            assertEquals(ACCOUNT_0_PUBLIC_KEY, key.publicKey.toHex())
            val signature = Ed25519Signer.sign(key.kL, signed)
            assertEquals(Ed25519Signer.SIGNATURE_BYTES, signature.size)
            assertTrue(Ed25519Signer.verify(key.publicKey, signed, signature))
            assertTrue(Ed25519Signer.verify(hexToBytes(ACCOUNT_0_PUBLIC_KEY), signed, signature))
        } finally {
            key.wipe()
        }
    }

    @Test
    fun theSignatureDoesNotVerifyForAnotherAccountOrWithoutTheTag() {
        val payment = AlgoTxnVectors.valid().single { it.name == "pay_basic" }
        val signed = bytesToSign(payment.raw)
        val key = LedgerEd25519.algorandAccount(seed(), 0)
        val other = LedgerEd25519.algorandAccount(seed(), 1)
        try {
            val signature = Ed25519Signer.sign(key.kL, signed)
            assertFalse(Ed25519Signer.verify(other.publicKey, signed, signature))
            assertFalse(Ed25519Signer.verify(key.publicKey, payment.raw, signature))
        } finally {
            key.wipe()
            other.wipe()
        }
    }

    @Test
    fun everySdkFixtureSentFromAccountZeroVerifiesWithTheAccountZeroPublicKey() {
        val key = LedgerEd25519.algorandAccount(seed(), 0)
        try {
            val sentFromAccountZero = AlgoTxnVectors.valid().filter {
                AlgoTxnDecoder.decode(it.raw).sender.contentEquals(hexToBytes(ACCOUNT_0_PUBLIC_KEY))
            }
            val names = sentFromAccountZero.map { it.name }
            assertTrue(
                names.containsAll(listOf("axfer", "axfer_optin", "axfer_closeout", "axfer_clawback", "axfer_mainnet_usdc")),
                "fixtures sent from account 0: $names",
            )
            for (v in sentFromAccountZero) {
                val signed = bytesToSign(v.raw)
                val signature = Ed25519Signer.sign(key.kL, signed)
                assertTrue(Ed25519Signer.verify(key.publicKey, signed, signature), v.name)
            }
        } finally {
            key.wipe()
        }
    }

    private fun seed(): ByteArray = Mnemonic.toSeed(PHRASE)

    private companion object {
        val PHRASE: List<String> = List(23) { "abandon" } + "art"
        const val ACCOUNT_0_PUBLIC_KEY = "7491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c6538"
    }
}
