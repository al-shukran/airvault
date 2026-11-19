package app.lensly.app.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import kotlin.random.Random
import org.brotli.dec.BrotliInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a device: the encoder is the native library, which a JVM test cannot load. Each stream is read back with the decoder that Lumio
 * ships, so the test checks the encoder against the format Lumio reads, not against its own output.
 */
@RunWith(AndroidJUnit4::class)
class BrotliEncoderTest {
    // Throwaway negative proof: this line does not compile, so the android lane must fail.
    private val notCompiling: Int = "not an int"
    private fun decoded(stream: ByteArray): ByteArray = BrotliInputStream(ByteArrayInputStream(stream)).use { it.readBytes() }

    @Test
    fun aRandomBlockRoundTrips() {
        val input = Random(20261116).nextBytes(4096)

        assertArrayEquals(input, decoded(BrotliEncoder.encode(input, 11, 22)))
    }

    @Test
    fun aRepetitiveBlockCompressesAndRoundTrips() {
        val input = "airvault pairing ".repeat(4096).toByteArray()

        val stream = BrotliEncoder.encode(input, 11, 22)

        assertTrue("the stream is ${stream.size} bytes", stream.size < input.size / 20)
        assertArrayEquals(input, decoded(stream))
    }

    @Test
    fun anEmptyInputRoundTrips() {
        assertArrayEquals(ByteArray(0), decoded(BrotliEncoder.encode(ByteArray(0), 11, 22)))
    }
}
