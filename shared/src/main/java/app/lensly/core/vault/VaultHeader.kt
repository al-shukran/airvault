package app.lensly.core.vault

import java.nio.ByteBuffer

/**
 * Contents of `header.bin`: the Argon2id parameters and salt for the password layer, the creation time
 * (which starts the 7-day learning period), and the Keystore facts recorded when the vault was created.
 *
 * Binary layout, big-endian: magic "LNVH" (4), version (1), memoryKiB (4), iterations (4), parallelism (4),
 * salt (16), createdAtWallMs (8), flags (1: bit 0 StrongBox feature, bit 1 hardware-backed), keySecurityLevel (4).
 */
class VaultHeader(
    val kdf: KdfParams,
    val salt: ByteArray,
    val createdAtWallMs: Long,
    val strongBox: Boolean,
    val hardwareBacked: Boolean,
    val keySecurityLevel: Int,
) {
    fun encode(): ByteArray {
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        val flags = (if (strongBox) FLAG_STRONGBOX else 0) or (if (hardwareBacked) FLAG_HARDWARE else 0)
        return ByteBuffer.allocate(SIZE)
            .put(MAGIC)
            .put(VERSION)
            .putInt(kdf.memoryKiB)
            .putInt(kdf.iterations)
            .putInt(kdf.parallelism)
            .put(salt)
            .putLong(createdAtWallMs)
            .put(flags.toByte())
            .putInt(keySecurityLevel)
            .array()
    }

    companion object {
        const val SALT_BYTES = 16
        private val MAGIC = "LNVH".toByteArray(Charsets.US_ASCII)
        private const val VERSION: Byte = 1
        private const val FLAG_STRONGBOX = 1
        private const val FLAG_HARDWARE = 2
        private const val SIZE = 4 + 1 + 4 + 4 + 4 + SALT_BYTES + 8 + 1 + 4

        /** Bounds that keep a damaged or hostile header from forcing an absurd Argon2 cost. */
        private const val MIN_MEMORY_KIB = 8 * 1024
        private const val MAX_MEMORY_KIB = 256 * 1024
        private const val MAX_ITERATIONS = 64

        fun decode(bytes: ByteArray): VaultHeader {
            if (bytes.size != SIZE) throw VaultException("The vault header has an unexpected size.")
            val buffer = ByteBuffer.wrap(bytes)
            val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
            if (!magic.contentEquals(MAGIC) || buffer.get() != VERSION) {
                throw VaultException("The vault header is not in a format this app reads.")
            }
            val kdf = KdfParams(
                memoryKiB = buffer.getInt(),
                iterations = buffer.getInt(),
                parallelism = buffer.getInt(),
            )
            val salt = ByteArray(SALT_BYTES).also { buffer.get(it) }
            val createdAt = buffer.getLong()
            val flags = buffer.get().toInt()
            val securityLevel = buffer.getInt()

            val validKdf = kdf.memoryKiB in MIN_MEMORY_KIB..MAX_MEMORY_KIB &&
                kdf.iterations in 1..MAX_ITERATIONS &&
                kdf.parallelism == 1
            if (!validKdf) throw VaultException("The vault header has Argon2 parameters out of range.")

            return VaultHeader(
                kdf = kdf,
                salt = salt,
                createdAtWallMs = createdAt,
                strongBox = flags and FLAG_STRONGBOX != 0,
                hardwareBacked = flags and FLAG_HARDWARE != 0,
                keySecurityLevel = securityLevel,
            )
        }
    }
}
