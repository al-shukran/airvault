package app.lensly.ui.camera

import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes the JPEG in [bytes] with its largest side at most about [maxSidePx]. ImageDecoder applies the EXIF orientation, so photos taken
 * in any rotation show upright. The bytes are plain photo bytes, which the caller zeroes after the call.
 */
internal fun decodePhoto(bytes: ByteArray, maxSidePx: Int): ImageBitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        val largest = maxOf(info.size.width, info.size.height)
        decoder.setTargetSampleSize(maxOf(1, largest / maxSidePx))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }.asImageBitmap()
} catch (e: IOException) {
    null
} catch (e: ImageDecoder.DecodeException) {
    null
}

/**
 * Shows a saved photo, or the first frame of a saved video, opened from [store] and decoded off the main thread. Shows an empty tile until
 * it is ready, and stays empty when [store] cannot open the file.
 */
@Composable
internal fun PhotoImage(
    store: PhotoStore,
    file: File,
    isVideo: Boolean,
    maxSidePx: Int,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, store, file, maxSidePx) {
        value = withContext(Dispatchers.IO) {
            if (isVideo) {
                store.videoSource(file)?.use { videoFrame(it, maxSidePx) }?.asImageBitmap()
            } else {
                store.photoBytes(file)?.let { bytes ->
                    try {
                        decodePhoto(bytes, maxSidePx)
                    } finally {
                        bytes.fill(0)
                    }
                }
            }
        }
    }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}
