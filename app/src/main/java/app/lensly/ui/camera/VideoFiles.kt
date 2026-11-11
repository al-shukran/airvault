package app.lensly.ui.camera

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.util.Size
import java.io.File
import java.io.IOException
import java.util.Locale

/** Length of a saved video in milliseconds, or null when the file cannot be read. */
internal fun videoDurationMs(file: File): Long? = try {
    MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(file.path)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    }
} catch (e: IllegalArgumentException) {
    null
}

/** The first frame of [file], scaled to fit within [maxSidePx], or null when no frame can be read. */
internal fun videoFrame(file: File, maxSidePx: Int): Bitmap? = try {
    ThumbnailUtils.createVideoThumbnail(file, Size(maxSidePx, maxSidePx), null)
} catch (e: IOException) {
    null
}

/** A clock-style length such as 0:07 or 12:30. */
fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
}
