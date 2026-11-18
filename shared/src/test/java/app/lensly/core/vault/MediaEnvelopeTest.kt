package app.lensly.core.vault

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A sealed file opens to the bytes it was sealed from, and any change to it, or a wrong key, is refused. */
class MediaEnvelopeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val privateKey = MediaEnvelope.newPrivateKey()
    private val publicKey = MediaEnvelope.publicKeyOf(privateKey)

    /** The envelope's layout: the header, then one cipher chunk of [CHUNK] plain bytes plus a [TAG] per chunk. */
    private val chunk = MediaEnvelope.CHUNK_BYTES
    private val header = 4 + 1 + MediaEnvelope.KEY_BYTES + 8
    private val tag = 16

    private fun sealed(plain: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        MediaEnvelope.seal(publicKey, ByteArrayInputStream(plain), out)
    }.toByteArray()

    /** Writes [bytes] to a file, and opens it with [key]. The caller closes the reader. */
    private fun open(bytes: ByteArray, key: ByteArray = privateKey): MediaEnvelope.SealedReader {
        val file = folder.newFile().apply { writeBytes(bytes) }
        return MediaEnvelope.SealedReader.open(file, key)
    }

    private fun flip(bytes: ByteArray, index: Int) {
        bytes[index] = (bytes[index].toInt() xor 0x01).toByte()
    }

    @Test
    fun anEmptyFileOpensToNoBytes() {
        open(sealed(ByteArray(0))).use { reader ->
            assertEquals(0L, reader.size)
            assertContentEquals(ByteArray(0), reader.readAll())
        }
    }

    @Test
    fun aSealedFileOpensToTheBytesItWasSealedFrom() {
        for (size in listOf(1, chunk - 1, chunk, chunk + 1, 3 * chunk + 7)) {
            val plain = Random(size).nextBytes(size)
            open(sealed(plain)).use { reader ->
                assertEquals(size.toLong(), reader.size, "size $size")
                assertContentEquals(plain, reader.readAll(), "size $size")
            }
        }
    }

    @Test
    fun readAtReturnsTheBytesAcrossAChunkBoundary() {
        val plain = Random(3).nextBytes(2 * chunk + 100)
        open(sealed(plain)).use { reader ->
            val buffer = ByteArray(40)

            assertEquals(40, reader.readAt(chunk - 20L, buffer, 0, 40))
            assertContentEquals(plain.copyOfRange(chunk - 20, chunk + 20), buffer)
            // A read that runs past the end gives what is left, and then -1.
            assertEquals(100, reader.readAt(2L * chunk, ByteArray(500), 0, 500))
            assertEquals(-1, reader.readAt(plain.size.toLong(), ByteArray(1), 0, 1))
        }
    }

    @Test
    fun aClosedReaderOpensNoBytes() {
        val reader = open(sealed(ByteArray(10)))

        reader.close()

        assertFailsWith<IOException> { reader.readAll() }
    }

    @Test
    fun aFileSealedToAnotherKeyIsRefused() {
        val other = MediaEnvelope.newPrivateKey()
        val bytes = sealed(Random(1).nextBytes(100))

        assertFailsWith<SealedMediaException> { open(bytes, other).use { it.readAll() } }
    }

    @Test
    fun aChangedByteFailsWhenItsChunkIsRead() {
        val plain = Random(2).nextBytes(chunk + 10)
        val bytes = sealed(plain)
        flip(bytes, header + chunk + tag + 5) // inside the second chunk

        open(bytes).use { reader ->
            val first = ByteArray(16)
            assertEquals(16, reader.readAt(0, first, 0, 16))
            assertContentEquals(plain.copyOfRange(0, 16), first)
            assertFailsWith<SealedMediaException> { reader.readAll() }
        }
    }

    @Test
    fun aChangedHeaderIsRefused() {
        val magic = sealed(ByteArray(5))
        flip(magic, 0)
        assertFailsWith<SealedMediaException> { open(magic) }

        // The ephemeral key and the nonce prefix are not checked on open. Every chunk fails when it is read.
        for (index in listOf(5 + 3, header - 1)) {
            val bytes = sealed(ByteArray(5))
            flip(bytes, index)
            assertFailsWith<SealedMediaException>("header byte $index") { open(bytes).use { it.readAll() } }
        }
    }

    @Test
    fun aFileThatIsNotSealedMediaIsRefused() {
        assertFailsWith<SealedMediaException> { open(ByteArray(10)) }
        assertFailsWith<SealedMediaException> { open(ByteArray(200)) }
        // A header with no chunk after it.
        assertFailsWith<SealedMediaException> { open(sealed(ByteArray(0)).copyOf(header)) }
    }

    @Test
    fun aFileCutShortAtAChunkBoundaryIsRefused() {
        val bytes = sealed(Random(4).nextBytes(chunk + 10))
        // The first chunk was sealed as a middle chunk. Cut after it, it is the last one, and its last flag no longer matches.
        val cut = bytes.copyOf(header + chunk + tag)

        open(cut).use { reader -> assertFailsWith<SealedMediaException> { reader.readAll() } }
    }

    @Test
    fun aFileCutShortInsideItsLastChunkIsRefused() {
        val bytes = sealed(Random(5).nextBytes(chunk + 10))
        // The last chunk is 26 bytes. Kept to 15 or 16, it has no plain byte, so the file is refused on open. Kept to 25, its tag fails when read.
        for (keep in listOf(15, 16, 25)) {
            val cut = bytes.copyOf(header + chunk + tag + keep)
            assertFailsWith<SealedMediaException>("keeping $keep bytes of the last chunk") { open(cut).use { it.readAll() } }
        }
    }

    @Test
    fun aFileExtendedByACopyOfItsLastChunkIsRefused() {
        val bytes = sealed(Random(6).nextBytes(chunk + 10))
        val lastChunk = bytes.copyOfRange(header + chunk + tag, bytes.size)

        open(bytes + lastChunk).use { reader -> assertFailsWith<SealedMediaException> { reader.readAll() } }
    }

    @Test
    fun chunksOutOfOrderAreRefused() {
        val bytes = sealed(Random(10).nextBytes(2 * chunk + 10))
        val first = bytes.copyOfRange(header, header + chunk + tag)
        val second = bytes.copyOfRange(header + chunk + tag, header + 2 * (chunk + tag))
        val swapped = bytes.copyOf()
        second.copyInto(swapped, header)
        first.copyInto(swapped, header + chunk + tag)

        open(swapped).use { reader -> assertFailsWith<SealedMediaException> { reader.readAll() } }
    }

    @Test
    fun sealingTheSameBytesTwiceGivesDifferentFiles() {
        val plain = Random(11).nextBytes(100)

        assertFalse(sealed(plain).contentEquals(sealed(plain)))
    }

    @Test
    fun theSealedBytesDoNotShowThePlainBytes() {
        val plain = "plain photo bytes, not for the folder".toByteArray()

        assertFalse(sealed(plain).holds(plain))
    }

    private fun ByteArray.holds(part: ByteArray): Boolean =
        (0..size - part.size).any { start -> part.indices.all { this[start + it] == part[it] } }
}
