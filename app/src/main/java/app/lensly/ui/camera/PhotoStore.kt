package app.lensly.ui.camera

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** App-private media folder (`filesDir/photos`) for photos and videos. Nothing is written to shared storage. */
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

        fun of(context: Context): PhotoStore = PhotoStore(File(context.filesDir, "photos"))
    }
}

/** True for a saved video. Videos are MP4 files; photos are JPEG. */
internal val File.isVideo: Boolean
    get() = extension == "mp4"
