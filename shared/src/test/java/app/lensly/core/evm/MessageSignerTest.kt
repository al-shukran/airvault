package app.lensly.core.evm

import app.lensly.core.ur.DataType
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign

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

    private fun signed(result: MessageSigner.Result): ByteArray =
        assertIs<MessageSigner.Result.Signed>(result).signature
}
