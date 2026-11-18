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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val SEALED_EXTENSION = "sealed"
private const val PHOTO_PREFIX = "IMG"
private const val VIDEO_PREFIX = "VID"

/**
 * One vault's app-private media folder. Every photo and video is a sealed file (see [MediaEnvelope]), so the folder holds no JPEG and no
 * MP4. The cover seals new media to the vault's public key, which needs no session. The gallery opens media with [privateKey], which the
 * gate holds while it is open, and [close] zeroes it. Nothing is written to shared storage.
 */
class PhotoStore(
    val directory: File,
    private val publicKeyFile: File,
    private var privateKey: ByteArray? = null,
) {

    /** Saved photos and videos, newest first. Names sort by capture time whatever their prefix. */
    fun list(): List<File> =
        directory.listFiles { file -> file.isFile && file.extension == SEALED_EXTENSION }
            ?.sortedByDescending { it.name.substringAfter('_') }
            ?: emptyList()

    /**
     * Seals one JPEG into a new photo file. [jpeg] is zeroed either way. Returns false when the photo could not be saved, and then no
     * file is left behind.
     */
    fun savePhoto(jpeg: ByteArray, nowMs: Long = System.currentTimeMillis()): Boolean = try {
        save(PHOTO_PREFIX, nowMs) { ByteArrayInputStream(jpeg) }
    } finally {
        jpeg.fill(0)
    }

    /**
     * Seals the MP4 at [recording] into a new video file, then shreds [recording], whether or not the video was saved.
     */
    fun saveVideo(recording: File, nowMs: Long = System.currentTimeMillis()): Boolean = try {
        save(VIDEO_PREFIX, nowMs) { recording.inputStream() }
    } finally {
        shredFile(recording)
    }

    /** The plain bytes of a saved photo, or null when this store cannot open it. The caller zeroes them. */
    fun photoBytes(file: File): ByteArray? = try {
        reader(file)?.use { it.readAll() }
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

    /** Seals what [open] reads into a new file named with [prefix]. The file appears only once it is whole. */
    private fun save(prefix: String, nowMs: Long, open: () -> InputStream): Boolean {
        val publicKey = readPublicKey() ?: return false
        val target = newFile(prefix, nowMs)
        return try {
            directory.mkdirs()
            val temp = File.createTempFile("${target.name}.", ".tmp", directory)
            try {
                FileOutputStream(temp).use { out ->
                    open().use { input -> MediaEnvelope.seal(publicKey, input, out) }
                    out.fd.sync()
                }
                commitTemp(temp, target)
            } catch (e: Throwable) {
                temp.delete()
                throw e
            }
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

    private fun newFile(prefix: String, nowMs: Long): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(nowMs)
        return File(directory, "${prefix}_$stamp.$SEALED_EXTENSION")
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

/** True for a saved video. A video's name starts with `VID_`; the file itself is sealed, so the name is the only sign of its kind. */
internal val File.isVideo: Boolean
    get() = name.startsWith("${VIDEO_PREFIX}_")

/** Overwrites [file] with random bytes, then deletes it. A file that cannot be overwritten is deleted all the same. True when it is gone. */
internal fun shredFile(file: File): Boolean {
    try {
        overwriteInPlace(file)
    } catch (e: IOException) {
        // Deleted below all the same.
    }
    return !file.exists() || file.delete()
}

/** The plain bytes of one sealed video, read through [reader]. Each read decrypts only the chunks it needs. */
private class SealedVideoSource(private val reader: MediaEnvelope.SealedReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        reader.readAt(position, buffer, offset, size)

    override fun getSize(): Long = reader.size

    override fun close() = reader.close()
}
