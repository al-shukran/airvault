package app.lensly.core.vault

import javax.crypto.AEADBadTagException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.Test

class AeadTest {
    private val key = ByteArray(32) { 7 }
    private val otherKey = ByteArray(32) { 8 }
    private val aad = "wallet-1".toByteArray()
    private val plaintext = "seed material".toByteArray()

    @Test
    fun roundTripsAndAddsIvAndTagOverhead() {
        val blob = Aead.seal(key, aad, plaintext)
        assertEquals(Aead.IV_BYTES + plaintext.size + 16, blob.size)
        assertContentEquals(plaintext, Aead.open(key, aad, blob))
    }

    @Test
    fun wrongKeyOrAadFailsAuthentication() {
        val blob = Aead.seal(key, aad, plaintext)
        assertFailsWith<AEADBadTagException> { Aead.open(otherKey, aad, blob) }
        assertFailsWith<AEADBadTagException> { Aead.open(key, "wallet-2".toByteArray(), blob) }
    }

    @Test
    fun tamperedBlobFailsAuthentication() {
        val blob = Aead.seal(key, aad, plaintext)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertFailsWith<AEADBadTagException> { Aead.open(key, aad, blob) }
    }

    @Test
    fun everySealUsesAFreshIv() {
        val first = Aead.seal(key, aad, plaintext)
        val second = Aead.seal(key, aad, plaintext)
        assertFalse(first.copyOf(Aead.IV_BYTES).contentEquals(second.copyOf(Aead.IV_BYTES)))
    }
}
