package app.lumio.pair

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The decoder Lumio reads a compressed body with. Its bound is what refuses a decompression bomb. */
class BrotliDecoderTest {

    /** A Brotli stream of 1 MiB of zero bytes: 14 bytes that inflate about 75,000 times. Made with the Python brotli reference, quality 11, window 22. */
    private val oneMebibyteOfZeros = "5bffff8f7f02201e0b0472ef1f00".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun aStreamThatInflatesPastTheBoundIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { BrotliDecoder.decompress(oneMebibyteOfZeros, 512 * 1024) }
    }

    @Test
    fun aStreamThatFitsTheBoundIsReadWhole() {
        assertArrayEquals(ByteArray(1 shl 20), BrotliDecoder.decompress(oneMebibyteOfZeros, 1 shl 20))
    }

    @Test
    fun aStreamOneByteOverTheBoundIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { BrotliDecoder.decompress(oneMebibyteOfZeros, (1 shl 20) - 1) }
    }

    @Test
    fun aTruncatedOrCorruptStreamIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            BrotliDecoder.decompress(oneMebibyteOfZeros.copyOf(oneMebibyteOfZeros.size - 3), 1 shl 20)
        }
        assertThrows(IllegalArgumentException::class.java) { BrotliDecoder.decompress(byteArrayOf(0x5b, 0x00, 0x00, 0x00), 1 shl 20) }
    }
}
