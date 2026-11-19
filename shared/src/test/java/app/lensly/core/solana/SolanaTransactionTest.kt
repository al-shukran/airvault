package app.lensly.core.solana

import app.airvault.chains.solana.signer.SolanaMessageReader
import app.lensly.core.wallet.toHex
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SolanaTransactionTest {

    private val message = SolanaVectors["legacy-sol-transfer"]
    private val signature = ByteArray(64) { (it + 1).toByte() }

    @Test
    fun aSingleSignatureIsCountedByOneByteThenTheSignatureThenTheMessage() {
        val signed = SolanaTransaction.signed(listOf(signature), message)

        assertEquals(1 + 64 + message.size, signed.size)
        assertEquals(0x01, signed[0].toInt())
        assertContentEquals(signature, signed.copyOfRange(1, 65))
        assertContentEquals(message, signed.copyOfRange(65, signed.size))
    }

    @Test
    fun theCountIsWrittenAsCompactU16AndReadBackByTheMessageReader() {
        val values = listOf(0, 1, 127, 128, 255, 16_383, 16_384, 65_535)
        val expected = mapOf(
            0 to "00",
            127 to "7f",
            128 to "8001",
            255 to "ff01",
            16_383 to "ff7f",
            16_384 to "808001",
            65_535 to "ffff03",
        )
        for (value in values) {
            val encoded = compactU16(value)
            expected[value]?.let { assertEquals(it, encoded.toHex(), "value $value") }
            val reader = SolanaMessageReader(encoded)
            assertEquals(value, reader.shortU16("count"), "value $value")
            assertEquals(0, reader.remaining, "value $value")
        }
    }

    @Test
    fun aTransactionWithTwoSignaturesCarriesBothInOrder() {
        val other = ByteArray(64) { 0x55 }
        val signed = SolanaTransaction.signed(listOf(signature, other), message)

        assertEquals(0x02, signed[0].toInt())
        assertContentEquals(signature, signed.copyOfRange(1, 65))
        assertContentEquals(other, signed.copyOfRange(65, 129))
        assertContentEquals(message, signed.copyOfRange(129, signed.size))
    }

    @Test
    fun noSignatureAndAWrongSizeSignatureAreRefused() {
        assertFailsWith<IllegalArgumentException> { SolanaTransaction.signed(emptyList(), message) }
        assertFailsWith<IllegalArgumentException> { SolanaTransaction.signed(listOf(signature.copyOf(63)), message) }
    }
}
