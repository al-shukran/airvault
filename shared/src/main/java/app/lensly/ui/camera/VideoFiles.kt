package app.lensly.ui.camera

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import java.util.Locale
import kotlin.math.roundToInt

/** Length of a saved video in milliseconds, or null when the video cannot be read. */
internal fun videoDurationMs(source: MediaDataSource): Long? = try {
    MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(source)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    }
} catch (e: RuntimeException) {
    // A corrupt or unsupported container is rejected at open, by the metadata retriever or by the codec.
    null
}

/** The first frame of a saved video, scaled to fit within [maxSidePx], or null when no frame can be read. */
internal fun videoFrame(source: MediaDataSource, maxSidePx: Int): Bitmap? = try {
    MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(source)
        retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { fitted(it, maxSidePx) }
    }
} catch (e: RuntimeException) {
    null
}

private fun fitted(frame: Bitmap, maxSidePx: Int): Bitmap {
    val largest = maxOf(frame.width, frame.height)
    if (largest <= maxSidePx) return frame
    val scale = maxSidePx.toFloat() / largest
    return Bitmap.createScaledBitmap(
        frame,
        (frame.width * scale).roundToInt().coerceAtLeast(1),
        (frame.height * scale).roundToInt().coerceAtLeast(1),
        true,
    )
}

/** A clock-style length such as 0:07 or 12:30. */
fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
}
