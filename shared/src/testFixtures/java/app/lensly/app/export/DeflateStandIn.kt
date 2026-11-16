package app.lensly.app.export

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A reversible stand-in for Brotli in the codec's tests. The JVM tests cannot load the native encoder, so they check the envelope with
 * this pair instead. The Brotli streams themselves are checked by Lumio's tests and by the encoder's instrumented test.
 */
object DeflateStandIn : BrotliCompressor, BrotliDecompressor {
    private const val CHUNK = 4096

    override fun compress(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK)
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        return out.toByteArray()
    }

    override fun decompress(bytes: ByteArray, maxBytes: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(chunk)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw IllegalArgumentException("truncated")
                if (out.size() + n > maxBytes) throw IllegalArgumentException("larger than $maxBytes bytes")
                out.write(chunk, 0, n)
            }
        } catch (e: DataFormatException) {
            throw IllegalArgumentException("not a stand-in stream", e)
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }
}
