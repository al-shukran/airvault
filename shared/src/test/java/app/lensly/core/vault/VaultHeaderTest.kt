package app.lensly.core.vault

import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test

class VaultHeaderTest {
    private val salt = ByteArray(VaultHeader.SALT_BYTES) { (it + 1).toByte() }

    /** A stand-in MAC for the codec tests. The real one is made by the Keystore (see [VaultKeystore]). */
    private fun digest(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

    private fun header(kdf: KdfParams = KdfParams(memoryKiB = 64 * 1024, iterations = 3)) = VaultHeader(
        kdf = kdf,
        salt = salt,
        createdAtWallMs = 1_800_000_000_123L,
        strongBox = true,
        hardwareBacked = true,
        keySecurityLevel = 2,
    )

    @Test
    fun roundTripsEveryField() {
        val decoded = VaultHeader.decode(header().encode())
        assertEquals(KdfParams(memoryKiB = 64 * 1024, iterations = 3), decoded.kdf)
        assertContentEquals(salt, decoded.salt)
        assertEquals(1_800_000_000_123L, decoded.createdAtWallMs)
        assertEquals(true, decoded.strongBox)
        assertEquals(true, decoded.hardwareBacked)
        assertEquals(2, decoded.keySecurityLevel)
    }

    @Test
    fun rejectsWrongMagic() {
        val bytes = header().encode()
        bytes[0] = 'X'.code.toByte()
        assertFailsWith<VaultException> { VaultHeader.decode(bytes) }
    }

    @Test
    fun anOlderVersionIsRefused() {
        // Version 1 is refused, and nothing is migrated from it.
        val bytes = header().encode()
        bytes[4] = 1
        assertFailsWith<VaultException> { VaultHeader.decode(bytes) }
    }

    @Test
    fun rejectsTruncatedHeader() {
        val bytes = header().encode()
        assertFailsWith<VaultException> { VaultHeader.decode(bytes.copyOf(bytes.size - 1)) }
    }

    @Test
    fun rejectsArgon2ParametersOutsideTheAcceptedRange() {
        assertFailsWith<VaultException> { VaultHeader.decode(header(KdfParams(memoryKiB = 1024, iterations = 1)).encode()) }
        assertFailsWith<VaultException> { VaultHeader.decode(header(KdfParams(memoryKiB = 64 * 1024, iterations = 0)).encode()) }
        assertFailsWith<VaultException> {
            VaultHeader.decode(header(KdfParams(memoryKiB = 64 * 1024, iterations = 1, parallelism = 4)).encode())
        }
    }

    @Test
    fun aFileWhoseMacVerifiesDecodesToItsFields() {
        val decoded = assertNotNull(VaultHeader.decodeFile(header().encodeFile { digest(it) }) { digest(it) })
        assertEquals(KdfParams(memoryKiB = 64 * 1024, iterations = 3), decoded.kdf)
        assertEquals(1_800_000_000_123L, decoded.createdAtWallMs)
    }

    @Test
    fun aFileWhoseMacDoesNotVerifyIsUnverifiable() {
        val bytes = header().encodeFile { digest(it) }
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
        assertNull(VaultHeader.decodeFile(bytes) { digest(it) })
    }

    @Test
    fun aFileOfTheOldLengthIsUnverifiable() {
        // A header written before the MAC holds the fields alone.
        assertNull(VaultHeader.decodeFile(header().encode()) { digest(it) })
    }

    @Test
    fun anOlderVersionIsUnverifiable() {
        // The version byte is MACed like every other field, so a header of another version is erased, not kept.
        val bytes = header().encodeFile { digest(it) }
        bytes[4] = 1
        assertNull(VaultHeader.decodeFile(bytes) { digest(it) })
    }
}
