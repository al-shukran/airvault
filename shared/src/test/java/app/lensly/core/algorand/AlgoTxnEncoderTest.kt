package app.lensly.core.algorand

import app.airvault.chains.algorand.Payment
import app.airvault.chains.algorand.signer.AlgoTxnDecoder
import app.airvault.chains.algorand.signer.AlgoTxnException
import app.lensly.core.wallet.toHex
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [AlgoTxnEncoder]. The payments below are the fields of the `pay_basic` and `pay_note_close` vectors, which
 * py-algorand-sdk 2.12.0 encoded (see the README in `vectors/algorand-txn`). The encoder must reproduce those bytes.
 */
class AlgoTxnEncoderTest {

    @Test
    fun `pay_basic is encoded byte for byte as the SDK encodes it`() {
        assertContentEquals(vector("pay_basic").raw, AlgoTxnEncoder.encodePayment(payment()))
    }

    @Test
    fun `pay_note_close is encoded byte for byte as the SDK encodes it`() {
        val txn = payment(amount = 2_000_000_000UL, closeTo = KEY_C, note = "rent for March".toByteArray())
        assertContentEquals(vector("pay_note_close").raw, AlgoTxnEncoder.encodePayment(txn))
    }

    @Test
    fun `every payment vector from the SDK decodes and encodes back to the same bytes`() {
        val payments = AlgoTxnVectors.valid().filter { AlgoTxnDecoder.decode(it.raw) is Payment }
        assertTrue(payments.size >= 3, "expected the payment vectors")
        for (v in payments) {
            assertContentEquals(v.raw, AlgoTxnEncoder.encodePayment(AlgoTxnDecoder.decode(v.raw) as Payment), v.name)
        }
    }

    @Test
    fun `decode of encode gives back the payment`() {
        val txn = payment(note = ByteArray(1024) { 0x41 }, closeTo = KEY_C, lease = ByteArray(32) { 3 }, rekeyTo = KEY_C)
        assertSamePayment(txn, AlgoTxnDecoder.decode(AlgoTxnEncoder.encodePayment(txn)) as Payment)
    }

    @Test
    fun `a genesis ID of 128 bytes and a note of 1024 bytes round trip`() {
        val txn = payment(genesisId = "g".repeat(128), note = ByteArray(1024) { 0x42 })
        assertSamePayment(txn, AlgoTxnDecoder.decode(AlgoTxnEncoder.encodePayment(txn)) as Payment)
    }

    @Test
    fun `zero fee and amount are left out of the map`() {
        val bytes = AlgoTxnEncoder.encodePayment(payment(fee = 0UL, amount = 0UL))
        // type, snd, fv, lv, gen, gh and rcv: seven entries in a fixmap, with no fee and no amt.
        assertEquals(0x80 or 7, bytes[0].toInt() and 0xff)
        assertEquals(0UL, (AlgoTxnDecoder.decode(bytes) as Payment).fee)
        assertEquals(0UL, (AlgoTxnDecoder.decode(bytes) as Payment).amount)
    }

    @Test
    fun `integers use the shortest format`() {
        val bytes = AlgoTxnEncoder.encodePayment(payment(amount = 300UL))
        assertTrue(contains(bytes, byteArrayOf(0xcd.toByte(), 0x01, 0x2c)), "300 is uint16")
        assertTrue(contains(AlgoTxnEncoder.encodePayment(payment(amount = 5UL)), byteArrayOf(0x05)), "5 is a positive fixint")
    }

    @Test
    fun `a note longer than 255 bytes uses bin16`() {
        val bytes = AlgoTxnEncoder.encodePayment(payment(note = ByteArray(300) { 1 }))
        assertTrue(contains(bytes, byteArrayOf(0xc5.toByte(), 0x01, 0x2c)), "300-byte note is bin16")
    }

    @Test
    fun `a payment the decoder refuses is not encoded`() {
        val error = assertFailsWith<AlgoTxnException> { AlgoTxnEncoder.encodePayment(payment(firstValid = 50_002_000UL)) }
        assertTrue(checkNotNull(error.message).contains("first valid round 50002000 is after last valid round 50001000"))
    }

    @Test
    fun `an all-zero receiver is refused`() {
        val txn = payment().copy(receiver = ByteArray(32))
        val error = assertFailsWith<AlgoTxnException> { AlgoTxnEncoder.encodePayment(txn) }
        assertTrue(checkNotNull(error.message).contains("'rcv' is zero or empty"))
    }

    @Test
    fun `a note of 1025 bytes is refused`() {
        assertFailsWith<AlgoTxnException> { AlgoTxnEncoder.encodePayment(payment(note = ByteArray(1025) { 1 })) }
    }

    @Test
    fun `an empty genesis ID is left out and decodes to empty`() {
        val txn = AlgoTxnDecoder.decode(AlgoTxnEncoder.encodePayment(payment(genesisId = "")))
        assertEquals("", txn.genesisId)
        assertNull((txn as Payment).closeTo)
    }

    private fun payment(
        fee: ULong = 1_000UL,
        amount: ULong = 1_500_000UL,
        firstValid: ULong = 50_000_000UL,
        lastValid: ULong = 50_001_000UL,
        genesisId: String = "testnet-v1.0",
        note: ByteArray = ByteArray(0),
        closeTo: ByteArray? = null,
        lease: ByteArray? = null,
        rekeyTo: ByteArray? = null,
    ) = Payment(
        sender = KEY_A,
        fee = fee,
        firstValid = firstValid,
        lastValid = lastValid,
        genesisId = genesisId,
        genesisHash = GENESIS_HASH,
        note = note,
        lease = lease,
        group = null,
        rekeyTo = rekeyTo,
        receiver = KEY_B,
        amount = amount,
        closeTo = closeTo,
    )

    private fun vector(name: String): AlgoTxnVectors.Valid = AlgoTxnVectors.valid().single { it.name == name }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { start -> needle.indices.all { haystack[start + it] == needle[it] } }

    private fun assertSamePayment(expected: Payment, actual: Payment) {
        assertContentEquals(expected.sender, actual.sender, "sender")
        assertEquals(expected.fee, actual.fee, "fee")
        assertEquals(expected.firstValid, actual.firstValid, "firstValid")
        assertEquals(expected.lastValid, actual.lastValid, "lastValid")
        assertEquals(expected.genesisId, actual.genesisId, "genesisId")
        assertContentEquals(expected.genesisHash, actual.genesisHash, "genesisHash")
        assertContentEquals(expected.note, actual.note, "note")
        assertEquals(expected.lease?.toHex(), actual.lease?.toHex(), "lease")
        assertEquals(expected.group?.toHex(), actual.group?.toHex(), "group")
        assertEquals(expected.rekeyTo?.toHex(), actual.rekeyTo?.toHex(), "rekeyTo")
        assertContentEquals(expected.receiver, actual.receiver, "receiver")
        assertEquals(expected.amount, actual.amount, "amount")
        assertEquals(expected.closeTo?.toHex(), actual.closeTo?.toHex(), "closeTo")
    }

    private companion object {
        val GENESIS_HASH: ByteArray = java.util.Base64.getDecoder().decode("SGO1GKSzyE7IEPItTxCByw9x8FmnrCDexi9/cOUJOiI=")
    }
}
