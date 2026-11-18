package app.lensly.ui.camera

import app.lensly.core.vault.VaultLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** One vault's app-private media folder for photos and videos. Nothing is written to shared storage. */
class PhotoStore(val directory: File) {

    /** A new, unused file for one JPEG. */
    fun newFile(nowMs: Long = System.currentTimeMillis()): File = newFile("IMG", "jpg", nowMs)

    /** A new, unused file for one MP4 video. */
    fun newVideoFile(nowMs: Long = System.currentTimeMillis()): File = newFile("VID", "mp4", nowMs)

    /** Saved photos and videos, newest first. Names sort by capture time whatever their prefix. */
    fun list(): List<File> =
        directory.listFiles { file -> file.isFile && file.extension in MEDIA_EXTENSIONS }
            ?.sortedByDescending { it.name.substringAfter('_') }
            ?: emptyList()

    fun delete(file: File): Boolean = file.delete()

    private fun newFile(prefix: String, extension: String, nowMs: Long): File {
        directory.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(nowMs)
        return File(directory, "${prefix}_$stamp.$extension")
    }

    companion object {
        private val MEDIA_EXTENSIONS = setOf("jpg", "mp4")

        /**
         * The real vault's media folder, or the Decoy Vault's when [decoy] is true. The two folders are kept apart, so a decoy lists
         * none of the real vault's photos. A wipe of the vault removes them (see [VaultLayout.photoDir]).
         */
        fun of(layout: VaultLayout, decoy: Boolean): PhotoStore = PhotoStore(layout.photoDir(decoy))
    }
}

/** True for a saved video. Videos are MP4 files; photos are JPEG. */
internal val File.isVideo: Boolean
    get() = extension == "mp4"
