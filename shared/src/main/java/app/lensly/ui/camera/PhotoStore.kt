package app.lensly.ui.camera

import android.media.MediaDataSource
import app.lensly.core.vault.MediaEnvelope
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.commitTemp
import app.lensly.core.vault.overwriteInPlace
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val TEMP_SUFFIX = ".tmp"

/**
 * The first [RECORD_BYTES] plain bytes of every saved file: its kind, then its capture time in milliseconds. They are sealed with the
 * media, so no name or modification time on disk shows what a file is or when it was taken.
 */
private const val RECORD_BYTES = 9
private const val PHOTO_KIND: Byte = 1
private const val VIDEO_KIND: Byte = 2

/** A saved photo or video. Its kind and capture time are read from the sealed file, so they need the private key. */
class SavedMedia(val file: File, val isVideo: Boolean, val capturedMs: Long)

/**
 * One vault's app-private media folder. Every photo and video is a sealed file (see [MediaEnvelope]) under an opaque name, so the folder
 * holds no JPEG and no MP4, and no name shows what a file is or when it was taken. The cover seals new media to the vault's public key,
 * which needs no session. The gallery opens media with [privateKey], which the gate holds while it is open, and [close] zeroes it.
 * Nothing is written to shared storage.
 */
class PhotoStore(
    val directory: File,
    private val publicKeyFile: File,
    private var privateKey: ByteArray? = null,
) {

    /**
     * Saved photos and videos, newest first. Each file's kind and capture time are sealed inside it, so a list needs the private key:
     * without one, the list is empty.
     */
    fun list(): List<SavedMedia> {
        val key = privateKey ?: return emptyList()
        return directory.listFiles { file -> file.isFile && !file.name.endsWith(TEMP_SUFFIX) }
            .orEmpty()
            .mapNotNull { file -> recordOf(file, key) }
            .sortedByDescending { it.capturedMs }
    }

    /**
     * Seals one JPEG into a new photo file. [jpeg] is zeroed either way. Returns false when the photo could not be saved, and then no
     * file is left behind.
     */
    fun savePhoto(jpeg: ByteArray, nowMs: Long = System.currentTimeMillis()): Boolean = try {
        save(PHOTO_KIND, nowMs) { ByteArrayInputStream(jpeg) }
    } finally {
        jpeg.fill(0)
    }

    /**
     * Seals the MP4 at [recording] into a new video file, then shreds [recording], whether or not the video was saved.
     */
    fun saveVideo(recording: File, nowMs: Long = System.currentTimeMillis()): Boolean = try {
        save(VIDEO_KIND, nowMs) { recording.inputStream() }
    } finally {
        shredFile(recording)
    }

    /** The plain bytes of a saved photo, or null when this store cannot open it. The caller zeroes them. */
    fun photoBytes(file: File): ByteArray? = try {
        reader(file)?.use { it.readAll() }?.let(::withoutRecord)
    } catch (e: IOException) {
        null
    }

    /**
     * The plain bytes of a saved video, as a source that the frame reader and the player read in parts. Null when this store cannot open
     * it. The caller closes it.
     */
    fun videoSource(file: File): MediaDataSource? = try {
        reader(file)?.let { SealedVideoSource(it) }
    } catch (e: IOException) {
        null
    }

    /** Overwrites a saved photo or video, then deletes it. */
    fun delete(file: File): Boolean = shredFile(file)

    /** Zeroes the private key, so that no key outlives the gallery that held it. */
    fun close() {
        privateKey?.fill(0)
        privateKey = null
    }

    private fun reader(file: File): MediaEnvelope.SealedReader? =
        privateKey?.let { MediaEnvelope.SealedReader.open(file, it) }

    /** Seals a record of [kind] and [nowMs], then what [open] reads, into a new file with an opaque name. The file appears only once it is whole. */
    private fun save(kind: Byte, nowMs: Long, open: () -> InputStream): Boolean {
        val publicKey = readPublicKey() ?: return false
        val record = ByteBuffer.allocate(RECORD_BYTES).put(kind).putLong(nowMs).array()
        val target = File(directory, UUID.randomUUID().toString())
        return try {
            directory.mkdirs()
            val temp = File.createTempFile("${target.name}.", TEMP_SUFFIX, directory)
            try {
                FileOutputStream(temp).use { out ->
                    open().use { input ->
                        MediaEnvelope.seal(publicKey, SequenceInputStream(ByteArrayInputStream(record), input), out)
                    }
                    out.fd.sync()
                }
                commitTemp(temp, target)
            } catch (e: Throwable) {
                temp.delete()
                throw e
            }
            // The modification time would show when the media was taken, so it is cleared.
            target.setLastModified(0L)
            true
        } catch (e: IOException) {
            false
        }
    }

    private fun readPublicKey(): ByteArray? = try {
        publicKeyFile.readBytes().takeIf { it.size == MediaEnvelope.KEY_BYTES }
    } catch (e: IOException) {
        null
    }

    companion object {
        /**
         * Seals captured media off the main thread, one file after another. A video can take seconds to seal. The thread is a daemon, so
         * a video still being sealed when the app leaves is finished in the background.
         */
        internal val sealer: Executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "lensly-seal").apply { isDaemon = true }
        }

        /**
         * The real vault's media folder, or the Decoy Vault's when [decoy] is true. The two folders are kept apart, so a decoy lists none
         * of the real vault's photos. A wipe of the vault removes them (see [VaultLayout.photoDir]). Only the gallery is given the
         * [privateKey]; the cover's store only saves.
         */
        fun of(layout: VaultLayout, decoy: Boolean, privateKey: ByteArray? = null): PhotoStore =
            PhotoStore(layout.photoDir(decoy), layout.photoKeyFile(decoy), privateKey)
    }
}

/** The kind and capture time of [file], or null when [key] cannot open it as saved media. */
private fun recordOf(file: File, key: ByteArray): SavedMedia? {
    val head = ByteArray(RECORD_BYTES)
    try {
        MediaEnvelope.SealedReader.open(file, key).use { reader ->
            if (reader.size < RECORD_BYTES || reader.readAt(0, head, 0, RECORD_BYTES) != RECORD_BYTES) return null
        }
    } catch (e: IOException) {
        return null
    }
    val buffer = ByteBuffer.wrap(head)
    val kind = buffer.get()
    if (kind != PHOTO_KIND && kind != VIDEO_KIND) return null
    return SavedMedia(file, isVideo = kind == VIDEO_KIND, capturedMs = buffer.getLong())
}

/** [plain] without the record that starts it. The whole array is zeroed, so only the media's own bytes are returned. */
private fun withoutRecord(plain: ByteArray): ByteArray {
    val body = plain.copyOfRange(RECORD_BYTES, plain.size)
    plain.fill(0)
    return body
}

/** Overwrites [file] with random bytes, then deletes it. A file that cannot be overwritten is deleted all the same. True when it is gone. */
internal fun shredFile(file: File): Boolean {
    try {
        overwriteInPlace(file)
    } catch (e: IOException) {
        // Deleted below all the same.
    }
    return !file.exists() || file.delete()
}

/** The plain bytes of one sealed video after its record, read through [reader]. Each read decrypts only the chunks it needs. */
private class SealedVideoSource(private val reader: MediaEnvelope.SealedReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        reader.readAt(position + RECORD_BYTES, buffer, offset, size)

    override fun getSize(): Long = reader.size - RECORD_BYTES

    override fun close() = reader.close()
}
