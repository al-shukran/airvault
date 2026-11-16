package app.lumio.pair

import app.lensly.app.export.BrotliDecompressor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.brotli.dec.BrotliInputStream

/**
 * Brotli decompression for the AirVault pairing, with org.brotli:dec: Google's pure-Java decoder, under the MIT licence. The output is read
 * in chunks and stops at the bound, so a stream that inflates past it is refused before it can fill memory (a decompression bomb). Lumio
 * ships no encoder.
 */
object BrotliDecoder : BrotliDecompressor {
    private const val CHUNK_BYTES = 4096

    override fun decompress(bytes: ByteArray, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var overflowed = false
        try {
            BrotliInputStream(ByteArrayInputStream(bytes)).use { input ->
                val chunk = ByteArray(CHUNK_BYTES)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) {
                        overflowed = true
                        break
                    }
                    out.write(chunk, 0, n)
                }
            }
        } catch (e: IOException) {
            throw IllegalArgumentException("The compressed pairing is not valid.", e)
        } catch (e: RuntimeException) {
            // The decoder's own runtime exception is package-private, so every failure of the decoder is caught here.
            throw IllegalArgumentException("The compressed pairing is not valid.", e)
        }
        require(!overflowed) { "The compressed pairing is larger than $maxBytes bytes." }
        return out.toByteArray()
    }
}
