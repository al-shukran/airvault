package app.lensly.ui.camera

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** App-private photo folder (`filesDir/photos`). Nothing is written to shared storage. */
class PhotoStore(val directory: File) {

    /** A new, unused file for one JPEG. Names sort by capture time, so newest-first is name-descending. */
    fun newFile(nowMs: Long = System.currentTimeMillis()): File {
        directory.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(nowMs)
        return File(directory, "IMG_$stamp.jpg")
    }

    /** Saved photos, newest first. */
    fun list(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(".jpg") }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    fun delete(file: File): Boolean = file.delete()

    companion object {
        fun of(context: Context): PhotoStore = PhotoStore(File(context.filesDir, "photos"))
    }
}
