package app.lensly.core.vault

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class VaultHeaderTest {
    private val salt = ByteArray(VaultHeader.SALT_BYTES) { (it + 1).toByte() }

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
}
