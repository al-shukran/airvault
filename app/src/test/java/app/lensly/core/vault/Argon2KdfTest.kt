package app.lensly.core.vault

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.junit.Test

class Argon2KdfTest {
    private val salt = ByteArray(16) { (it * 3).toByte() }
    private val params = KdfParams(memoryKiB = 8 * 1024, iterations = 1)

    @Test
    fun producesThirtyTwoBytesDeterministically() {
        val first = Argon2Kdf.derive("correct horse battery".toCharArray(), salt, params)
        val second = Argon2Kdf.derive("correct horse battery".toCharArray(), salt, params)
        assertEquals(32, first.size)
        assertTrue(first.contentEquals(second))
    }

    @Test
    fun passwordAndSaltBothAffectTheKey() {
        val base = Argon2Kdf.derive("alpha-password-1".toCharArray(), salt, params)
        val otherPassword = Argon2Kdf.derive("alpha-password-2".toCharArray(), salt, params)
        val otherSalt = Argon2Kdf.derive("alpha-password-1".toCharArray(), salt.copyOf().also { it[0] = 9 }, params)
        assertFalse(base.contentEquals(otherPassword))
        assertFalse(base.contentEquals(otherSalt))
    }

    @Test
    fun hashesTheUtf8EncodingOfThePassword() {
        val password = "pässwörd-日本-😀"
        val expected = ByteArray(Argon2Kdf.OUTPUT_BYTES)
        Argon2BytesGenerator().apply {
            init(
                Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withSalt(salt)
                    .withIterations(params.iterations)
                    .withMemoryAsKB(params.memoryKiB)
                    .withParallelism(params.parallelism)
                    .build(),
            )
            generateBytes(password.toByteArray(Charsets.UTF_8), expected)
        }
        assertTrue(expected.contentEquals(Argon2Kdf.derive(password.toCharArray(), salt, params)))
    }

    @Test
    fun leavesThePasswordArrayUnchanged() {
        val password = "unchanged-password".toCharArray()
        Argon2Kdf.derive(password, salt, params)
        assertEquals("unchanged-password", password.concatToString())
    }
}
