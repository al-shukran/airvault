package app.lensly.core.vault

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.Arrays
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/** Argon2id cost parameters. They are stored in the vault header. */
internal data class KdfParams(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int = 1,
)

/** Argon2id, version 1.3, through BouncyCastle. Produces the 32-byte software key K_sw. */
internal object Argon2Kdf {
    const val OUTPUT_BYTES = 32

    /**
     * Derives [OUTPUT_BYTES] from the UTF-8 encoding of [password]. The UTF-8 copy is zeroed afterwards.
     * [password] itself is not modified.
     */
    fun derive(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        val passwordBytes = utf8Bytes(password)
        try {
            val generator = Argon2BytesGenerator()
            generator.init(
                Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withSalt(salt)
                    .withIterations(params.iterations)
                    .withMemoryAsKB(params.memoryKiB)
                    .withParallelism(params.parallelism)
                    .build(),
            )
            val out = ByteArray(OUTPUT_BYTES)
            generator.generateBytes(passwordBytes, out)
            return out
        } finally {
            Arrays.fill(passwordBytes, 0)
        }
    }

    private fun utf8Bytes(chars: CharArray): ByteArray {
        val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        if (encoded.hasArray()) Arrays.fill(encoded.array(), 0)
        return bytes
    }
}
