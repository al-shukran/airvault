package app.lensly.core.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** HKDF against the RFC 5869 known-answer vectors, and the keys that [VaultSession.deriveKey] derives from a Vault Key. */
class DeriveKeyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val okm = hkdfSha256(
            ikm = ByteArray(22) { 0x0b },
            salt = ByteArray(13) { it.toByte() },
            info = ByteArray(10) { (0xf0 + it).toByte() },
            length = 42,
        )

        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.toHex())
    }

    @Test
    fun hkdfMatchesRfc5869TestCase3WithoutSaltOrInfo() {
        val okm = hkdfSha256(ikm = ByteArray(22) { 0x0b }, salt = null, info = ByteArray(0), length = 42)

        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", okm.toHex())
    }

    @Test
    fun aPurposeGivesTheSameKeyEachTimeAndAnotherKeyForAnotherPurpose() {
        val session = session(seed = 1)
        val mac = session.deriveKey("lumio-cache-v1/mac")

        assertEquals(32, mac.size)
        assertArrayEquals(mac, session.deriveKey("lumio-cache-v1/mac"))
        assertFalse(mac.contentEquals(session.deriveKey("lumio-cache-v1/enc")))
    }

    @Test
    fun aDerivedKeyIsNotTheVaultKey() {
        val session = session(seed = 1)

        assertFalse(session.deriveKey("lumio-cache-v1/mac").contentEquals(session.copyOfVaultKey()))
    }

    @Test
    fun aVaultWithAnotherKeyDerivesAnotherKeyForTheSamePurpose() {
        val mac = session(seed = 1).deriveKey("lumio-cache-v1/mac")

        assertFalse(mac.contentEquals(session(seed = 2).deriveKey("lumio-cache-v1/mac")))
    }

    private fun session(seed: Int): VaultSession =
        VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 7 + seed).toByte() })

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
