package app.lensly.ui.camera

import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.lensly.ui.theme.LenslySurface
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes [file] with its largest side at most about [maxSidePx]. ImageDecoder applies the
 * EXIF orientation, so photos taken in any rotation show upright.
 */
internal fun decodePhoto(file: File, maxSidePx: Int): ImageBitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val largest = maxOf(info.size.width, info.size.height)
        decoder.setTargetSampleSize(maxOf(1, largest / maxSidePx))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }.asImageBitmap()
} catch (e: IOException) {
    null
} catch (e: ImageDecoder.DecodeException) {
    null
}

/** Shows a saved photo, or the first frame of a saved video, decoded off the main thread. Shows an empty tile until it is ready. */
@Composable
internal fun PhotoImage(
    file: File,
    maxSidePx: Int,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file, maxSidePx) {
        value = withContext(Dispatchers.IO) {
            if (file.isVideo) videoFrame(file, maxSidePx)?.asImageBitmap() else decodePhoto(file, maxSidePx)
        }
    }
    Box(modifier = modifier.background(LenslySurface)) {
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
