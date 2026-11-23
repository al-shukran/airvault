package app.lensly.core.vault

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Contents of `header.bin`: the Argon2id parameters and salt for the password layer, the creation time
 * (which starts the 7-day learning period), and the Keystore facts recorded when the vault was created.
 *
 * The fields are big-endian: magic "LNVH" (4), version (1), memoryKiB (4), iterations (4), parallelism (4),
 * salt (16), createdAtWallMs (8), flags (1: bit 0 StrongBox feature, bit 1 hardware-backed), keySecurityLevel (4).
 * The file is those fields, then a MAC of them under the header key (see [VaultKeystore]). A file whose MAC does not verify is
 * unverifiable, and the vault is erased before any password is checked (see [Vault]).
 */
class VaultHeader(
    val kdf: KdfParams,
    val salt: ByteArray,
    val createdAtWallMs: Long,
    val strongBox: Boolean,
    val hardwareBacked: Boolean,
    val keySecurityLevel: Int,
) {
    /** The fields, in the layout of the class comment. */
    fun encode(): ByteArray {
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        val flags = (if (strongBox) FLAG_STRONGBOX else 0) or (if (hardwareBacked) FLAG_HARDWARE else 0)
        return ByteBuffer.allocate(FIELDS_BYTES)
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

    /** The whole file: the [encode]d fields, then the MAC that [mac] makes of the MAC input for them. */
    fun encodeFile(mac: (ByteArray) -> ByteArray): ByteArray {
        val fields = encode()
        return fields + mac(macInput(fields))
    }

    companion object {
        const val SALT_BYTES = 16

        /** Bytes of the fields, see [encode]. */
        const val FIELDS_BYTES = 4 + 1 + 4 + 4 + 4 + SALT_BYTES + 8 + 1 + 4

        /** Bytes of the MAC that ends `header.bin`. */
        const val MAC_BYTES = 32

        /** Bytes of `header.bin`: the fields, then the MAC. */
        const val FILE_BYTES = FIELDS_BYTES + MAC_BYTES

        private val MAGIC = "LNVH".toByteArray(Charsets.US_ASCII)
        private const val VERSION: Byte = VAULT_FORMAT_VERSION
        private const val VERSION_INDEX = 4
        private const val FLAG_STRONGBOX = 1
        private const val FLAG_HARDWARE = 2

        /** Starts the MAC input, so the header MAC is not any other MAC under the header key. */
        private val MAC_LABEL = "lensly.vault.header.v3".toByteArray(Charsets.US_ASCII)

        /** Bounds that keep a damaged or hostile header from forcing an absurd Argon2 cost. */
        private const val MIN_MEMORY_KIB = 8 * 1024
        private const val MAX_MEMORY_KIB = 256 * 1024
        private const val MAX_ITERATIONS = 64

        private fun macInput(fields: ByteArray): ByteArray = MAC_LABEL + fields

        /** Parses the [FIELDS_BYTES] of the fields. A file is read by [decodeFile]. */
        fun decode(bytes: ByteArray): VaultHeader {
            if (bytes.size != FIELDS_BYTES) throw VaultException("The vault header has an unexpected size.")
            val buffer = ByteBuffer.wrap(bytes)
            val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
            if (!magic.contentEquals(MAGIC)) throw VaultException("The vault header is not in a format this application reads.")
            if (buffer.get() != VERSION) throw VaultFormatChangedException()
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

        /**
         * Parses `header.bin` as [encodeFile] wrote it. [mac] makes the MAC of a MAC input. A file of another format version is refused as a
         * format change before its MAC is read, as before (see [VaultFormatChangedException]). Any other file that is not [FILE_BYTES] long,
         * or whose MAC does not verify, is unverifiable, and the result is null.
         */
        fun decodeFile(file: ByteArray, mac: (ByteArray) -> ByteArray): VaultHeader? {
            if (file.size > VERSION_INDEX && file[VERSION_INDEX] != VERSION) throw VaultFormatChangedException()
            if (file.size != FILE_BYTES) return null
            val fields = file.copyOf(FIELDS_BYTES)
            val expected = mac(macInput(fields))
            if (!MessageDigest.isEqual(expected, file.copyOfRange(FIELDS_BYTES, FILE_BYTES))) return null
            return decode(fields)
        }
    }
}
