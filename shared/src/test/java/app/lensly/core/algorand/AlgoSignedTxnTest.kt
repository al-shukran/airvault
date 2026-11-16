package app.lensly.core.algorand

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [AlgoSignedTxn]. The transaction IDs were computed outside this code: base32 of the SHA-512/256 hash of
 * "TX" and the bytes of the `pay_basic` and `pay_note_close` vectors, which py-algorand-sdk 2.12.0 encoded (see the README in
 * `vectors/algorand-txn`), with python3's hashlib and base64. That is the same definition the network uses.
 */
class AlgoSignedTxnTest {
    private val raw: ByteArray by lazy { vector("pay_basic").raw }
    private val seed = ByteArray(32) { 7 }
    private val key = Ed25519PrivateKeyParameters(seed, 0)
    private val publicKey = key.generatePublicKey().encoded

    @Test
    fun `the transaction ID of pay_basic is the one the network computes`() {
        assertEquals("VOLN5LOJUU2NAHUPUHYMFX5TXEZI4WIHJDUOLLXGLO6MX53SUEUA", AlgoSignedTxn.txid(raw))
    }

    @Test
    fun `the transaction ID of pay_note_close is the one the network computes`() {
        assertEquals("HXSZDTPJ4ZJX42T7QQNDKNL74TB4M7VK5FAVNZ2D7MKGVDSV5XDA", AlgoSignedTxn.txid(vector("pay_note_close").raw))
    }

    @Test
    fun `a transaction ID is 52 base32 characters`() {
        assertEquals(52, AlgoSignedTxn.txid(raw).length)
    }

    @Test
    fun `the signed transaction is the map of sig and txn with the bytes that were signed`() {
        val signature = ByteArray(64) { it.toByte() }
        val expected = byteArrayOf(0x82.toByte(), 0xa3.toByte()) + "sig".toByteArray() +
            byteArrayOf(0xc4.toByte(), 0x40) + signature +
            byteArrayOf(0xa3.toByte()) + "txn".toByteArray() + raw
        assertContentEquals(expected, AlgoSignedTxn.assemble(raw, signature))
    }

    @Test
    fun `assembling refuses bytes that are not a transaction and a signature of the wrong length`() {
        assertFailsWith<AlgoTxnException> { AlgoSignedTxn.assemble(byteArrayOf(0x01), ByteArray(64)) }
        assertFailsWith<IllegalArgumentException> { AlgoSignedTxn.assemble(raw, ByteArray(63)) }
    }

    @Test
    fun `a signature over TX and the transaction verifies`() {
        assertTrue(AlgoSignedTxn.verify(publicKey, raw, sign(bytesToSign(raw))))
    }

    @Test
    fun `a wrong signature is refused`() {
        val good = sign(bytesToSign(raw))
        val flipped = good.copyOf().also { it[10] = (it[10].toInt() xor 0x01).toByte() }
        assertFalse(AlgoSignedTxn.verify(publicKey, raw, flipped))
        assertFalse(AlgoSignedTxn.verify(publicKey, raw, ByteArray(64)))
        assertFalse(AlgoSignedTxn.verify(publicKey, raw, good.copyOf(63)))
    }

    @Test
    fun `a signature over the transaction without the TX tag is refused`() {
        assertFalse(AlgoSignedTxn.verify(publicKey, raw, sign(raw)))
    }

    @Test
    fun `a signature for another transaction or another account is refused`() {
        val signature = sign(bytesToSign(raw))
        assertFalse(AlgoSignedTxn.verify(publicKey, vector("pay_note_close").raw, signature))
        val other = Ed25519PrivateKeyParameters(ByteArray(32) { 9 }, 0).generatePublicKey().encoded
        assertFalse(AlgoSignedTxn.verify(other, raw, signature))
        assertFalse(AlgoSignedTxn.verify(publicKey.copyOf(31), raw, signature))
    }

    private fun sign(message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, key)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    private fun vector(name: String): AlgoTxnVectors.Valid = AlgoTxnVectors.valid().single { it.name == name }
}
