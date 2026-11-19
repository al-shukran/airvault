package app.lensly.core.evm

import app.airvault.chains.evm.signer.DataType
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric

class MessageSignerTest {

    private val key = EvmFixtures.artKey
    private val message = "Lensly sign-in".toByteArray(Charsets.UTF_8)

    @Test
    fun personalSignRecoversToAccountZero() {
        val signature = signed(MessageSigner.sign(DataType.PERSONAL_MESSAGE, message, key))

        assertEquals(65, signature.size)
        val v = signature[64].toInt() and 0xFF
        assertTrue(v == 27 || v == 28, "v must be 27 or 28, was $v")
        val publicKey = Sign.signedPrefixedMessageToKey(
            message,
            Sign.SignatureData(v.toByte(), signature.copyOfRange(0, 32), signature.copyOfRange(32, 64)),
        )
        assertEquals(EvmFixtures.normalize(EvmFixtures.ART_ADDRESS), EvmFixtures.normalize(Keys.getAddress(publicKey)))
    }

    @Test
    fun matchesWeb3jPrefixedSigning() {
        val expected = Sign.signPrefixedMessage(message, ECKeyPair.create(key))
        val signature = signed(MessageSigner.sign(DataType.PERSONAL_MESSAGE, message, key))

        assertEquals(BigInteger(1, expected.r), BigInteger(1, signature.copyOfRange(0, 32)))
        assertEquals(BigInteger(1, expected.s), BigInteger(1, signature.copyOfRange(32, 64)))
        assertEquals(expected.v[0], signature[64])
    }

    @Test
    fun refusesTypedDataAndTransactions() {
        assertIs<MessageSigner.Result.Refused>(MessageSigner.sign(DataType.TYPED_DATA, message, key))
        assertIs<MessageSigner.Result.Refused>(MessageSigner.sign(DataType.TRANSACTION, message, key))
        assertIs<MessageSigner.Result.Refused>(MessageSigner.sign(DataType.TYPED_TRANSACTION, message, key))
    }

    @Test
    fun theLighterApiKeyMessageHasItsReferenceSignature() {
        // lighter-go, types/txtypes/utils.go (TemplateChangePubKey), filled with public key bytes 01..28, nonce 5, account 3 and key index 2.
        // The reference is `cast wallet sign` of the same text (EIP-191, 255 bytes) with the hyperliquid SDK test key.
        val text = "Register Lighter Account\n\npubkey: 0x" + (1..40).joinToString("") { "%02x".format(it) } +
            "\nnonce: 0x0000000000000005\naccount index: 0x0000000000000003\napi key index: 0x0000000000000002\n" +
            "Only sign this message for a trusted client!"

        val signature = signed(MessageSigner.sign(DataType.PERSONAL_MESSAGE, text.toByteArray(Charsets.UTF_8), SDK_TEST_KEY))

        assertEquals(
            "0xe1bdea38fd22db0a55de6da7a5eddfd69852de412a78ddb697023da32cd7d5502b52e514a4fa4fe46169e7233511f865988e3a14fae1a49c7554bbd5392434071b",
            Numeric.toHexString(signature),
        )
    }

    private fun signed(result: MessageSigner.Result): ByteArray =
        assertIs<MessageSigner.Result.Signed>(result).signature
}

/** The test key of hyperliquid-python-sdk, tests/signing_test.py. It has never held funds. */
private val SDK_TEST_KEY = Numeric.hexStringToByteArray("0x0123456789012345678901234567890123456789012345678901234567890123")
