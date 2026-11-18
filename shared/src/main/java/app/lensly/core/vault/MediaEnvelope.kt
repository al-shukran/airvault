package app.lensly.core.vault

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/** A sealed file that is damaged, was sealed to another key, or was changed after it was sealed. Such a file is refused. */
class SealedMediaException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Sealed media: the envelope that holds one photo or video on disk. [seal] needs only the vault's public key, so the cover can seal with
 * no session open. [SealedReader] opens a file with the vault's private key, which exists only while the vault is unlocked.
 *
 * A file is a header, then the body in chunks of [CHUNK_BYTES] plain bytes. The header is the magic `LNSM`, the version (1), an
 * ephemeral X25519 public key (32 bytes) and an 8-byte nonce prefix. The key of a file is HKDF-SHA256 of the X25519 shared secret of the
 * ephemeral key and the vault's public key, salted with both public keys. So every file has a key of its own, and only the vault's
 * private key derives it. Chunk `i` is AES-256-GCM under that key, with nonce = prefix || `i` (big-endian) and AAD = header || last flag.
 * The header is in every AAD, so changing it fails. The last flag fails a file cut short or extended at a chunk boundary.
 */
object MediaEnvelope {
    const val KEY_BYTES = 32

    /** Plain bytes per chunk. Every chunk but the last is this long. */
    internal const val CHUNK_BYTES = 64 * 1024

    private val MAGIC = "LNSM".toByteArray(StandardCharsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val PREFIX_BYTES = 8
    private const val HEADER_BYTES = 4 + 1 + KEY_BYTES + PREFIX_BYTES
    private const val TAG_BYTES = 16
    private const val CHUNK_CIPHER_BYTES = CHUNK_BYTES + TAG_BYTES
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val INFO = "lensly.media.v1".toByteArray(StandardCharsets.US_ASCII)
    private val random = SecureRandom()

    /** A new X25519 private key of [KEY_BYTES] bytes. The caller zeroes it when it is no longer needed. */
    fun newPrivateKey(): ByteArray = X25519PrivateKeyParameters(random).encoded

    /** The public key of [privateKey]. Captures seal to it. */
    fun publicKeyOf(privateKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_BYTES) { "A private key is $KEY_BYTES bytes." }
        return X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
    }

    /**
     * Writes everything [input] holds to [output], sealed to [publicKey]. An empty input makes a file with one empty chunk. The
     * ephemeral key is discarded when the call returns.
     *
     * @throws SealedMediaException if the media cannot be sealed.
     */
    fun seal(publicKey: ByteArray, input: InputStream, output: OutputStream) {
        require(publicKey.size == KEY_BYTES) { "A public key is $KEY_BYTES bytes." }
        val ephemeralPrivate = newPrivateKey()
        val ephemeralPublic = publicKeyOf(ephemeralPrivate)
        val shared = sharedSecret(ephemeralPrivate, publicKey)
        ephemeralPrivate.fill(0)
        val key = fileKey(shared, ephemeralPublic, publicKey)
        shared.fill(0)
        val prefix = ByteArray(PREFIX_BYTES).also { random.nextBytes(it) }
        val header = MAGIC + byteArrayOf(VERSION) + ephemeralPublic + prefix
        try {
            output.write(header)
            // A chunk is sealed once the next one is read, so the last chunk is known and marked as such.
            var current = readChunk(input)
            var index = 0
            while (true) {
                val next = readChunk(input)
                val last = next.isEmpty()
                output.write(transform(Cipher.ENCRYPT_MODE, key, prefix, header, index, last, current))
                current.fill(0)
                if (last) break
                current = next
                index++
            }
        } catch (e: GeneralSecurityException) {
            throw SealedMediaException("The media could not be sealed.", e)
        } finally {
            key.fill(0)
        }
    }

    private fun readChunk(input: InputStream): ByteArray {
        val buffer = ByteArray(CHUNK_BYTES)
        var filled = 0
        while (filled < CHUNK_BYTES) {
            val n = input.read(buffer, filled, CHUNK_BYTES - filled)
            if (n < 0) break
            filled += n
        }
        return buffer.copyOf(filled).also { buffer.fill(0) }
    }

    private fun transform(
        mode: Int,
        key: ByteArray,
        prefix: ByteArray,
        header: ByteArray,
        index: Int,
        last: Boolean,
        input: ByteArray,
    ): ByteArray {
        val nonce = ByteBuffer.allocate(PREFIX_BYTES + 4).put(prefix).putInt(index).array()
        val lastFlag: Byte = if (last) 1 else 0
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(header + lastFlag)
        return cipher.doFinal(input)
    }

    private fun sharedSecret(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        val shared = ByteArray(agreement.agreementSize)
        try {
            agreement.calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), shared, 0)
        } catch (e: IllegalStateException) {
            // BouncyCastle refuses a secret that is all zeros, which a low-order public key gives.
            shared.fill(0)
            throw SealedMediaException("The media key is not valid.", e)
        }
        return shared
    }

    private fun fileKey(shared: ByteArray, ephemeralPublic: ByteArray, vaultPublic: ByteArray): ByteArray =
        hkdfSha256(shared, salt = ephemeralPublic + vaultPublic, info = INFO, length = KEY_BYTES)

    /**
     * One sealed file, opened with the vault's private key and read a chunk at a time, so the plain bytes of a video are never written to
     * storage. A chunk is authenticated when it is read. Create one with [open], and call [close] to zero its file key.
     */
    class SealedReader private constructor(
        private val raf: RandomAccessFile,
        private val header: ByteArray,
        private val prefix: ByteArray,
        private var key: ByteArray?,
        private val chunks: Int,
        private val lastCipherBytes: Int,
        /** The size of the plain file, in bytes. */
        val size: Long,
    ) : Closeable {
        private var cachedIndex = -1
        private var cachedPlain = ByteArray(0)

        /**
         * Copies plain bytes starting at [position] into [buffer]. Returns the count copied, or -1 at the end of the file.
         *
         * @throws IOException if the reader is closed, or a chunk it needs is damaged.
         */
        @Synchronized
        fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            var done = 0
            var at = position
            while (done < length && at < size) {
                val index = (at / CHUNK_BYTES).toInt()
                val plain = chunk(index)
                val within = (at - index.toLong() * CHUNK_BYTES).toInt()
                val n = minOf(length - done, plain.size - within)
                plain.copyInto(buffer, offset + done, within, within + n)
                done += n
                at += n
            }
            return done
        }

        /** The whole plain file. Used for photos; a video is read in parts with [readAt]. */
        @Synchronized
        fun readAll(): ByteArray {
            require(size <= Int.MAX_VALUE) { "The media is too large to read at once." }
            val out = ByteArray(size.toInt())
            var at = 0
            while (at < out.size) {
                val n = readAt(at.toLong(), out, at, out.size - at)
                if (n <= 0) throw damaged()
                at += n
            }
            return out
        }

        @Synchronized
        override fun close() {
            key?.fill(0)
            key = null
            cachedPlain.fill(0)
            cachedPlain = ByteArray(0)
            cachedIndex = -1
            raf.close()
        }

        private fun chunk(index: Int): ByteArray {
            if (index == cachedIndex) return cachedPlain
            val fileKey = key ?: throw IOException("The sealed media is closed.")
            val last = index == chunks - 1
            val cipherText = ByteArray(if (last) lastCipherBytes else CHUNK_CIPHER_BYTES)
            raf.seek(HEADER_BYTES.toLong() + index.toLong() * CHUNK_CIPHER_BYTES)
            raf.readFully(cipherText)
            val plain = try {
                transform(Cipher.DECRYPT_MODE, fileKey, prefix, header, index, last, cipherText)
            } catch (e: GeneralSecurityException) {
                throw damaged(e)
            }
            cachedPlain.fill(0)
            cachedPlain = plain
            cachedIndex = index
            return plain
        }

        companion object {
            /**
             * Opens [file] with [privateKey].
             *
             * @throws SealedMediaException if the file is damaged, or was sealed to another key.
             * @throws IOException if the file cannot be read.
             */
            fun open(file: File, privateKey: ByteArray): SealedReader {
                val raf = RandomAccessFile(file, "r")
                try {
                    val body = raf.length() - HEADER_BYTES
                    if (body < TAG_BYTES) throw damaged()
                    val header = ByteArray(HEADER_BYTES)
                    raf.readFully(header)
                    if (!header.copyOfRange(0, 4).contentEquals(MAGIC) || header[4] != VERSION) throw damaged()
                    val ephemeralPublic = header.copyOfRange(5, 5 + KEY_BYTES)
                    val prefix = header.copyOfRange(5 + KEY_BYTES, HEADER_BYTES)
                    val shared = sharedSecret(privateKey, ephemeralPublic)
                    val key = fileKey(shared, ephemeralPublic, publicKeyOf(privateKey))
                    shared.fill(0)
                    val chunks = ((body + CHUNK_CIPHER_BYTES - 1) / CHUNK_CIPHER_BYTES).toInt()
                    val lastCipherBytes = (body - (chunks - 1).toLong() * CHUNK_CIPHER_BYTES).toInt()
                    val size = (chunks - 1).toLong() * CHUNK_BYTES + lastCipherBytes - TAG_BYTES
                    return SealedReader(raf, header, prefix, key, chunks, lastCipherBytes, size)
                } catch (e: Throwable) {
                    raf.close()
                    throw e
                }
            }
        }
    }

    private fun damaged(cause: Throwable? = null) =
        SealedMediaException("The media is damaged, or was sealed to another key.", cause)
}
