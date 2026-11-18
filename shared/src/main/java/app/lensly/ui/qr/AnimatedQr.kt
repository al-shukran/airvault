package app.lensly.ui.qr

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.PillChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val CARD_CORNER = 24.dp
private val CARD_PADDING = 16.dp
private val CARD_PAPER = Color.White

/**
 * Shows one or more QR codes as a black-on-white card, the layout scanners expect.
 *
 * With more than one part, the parts cycle every [frameMillis] and a "PART 3 OF 12" chip sits
 * under the card. The parts are encoded off the main thread and the frames are kept in [QrFrameCache], so a
 * revisit shows them at once. Until they are ready the card is blank at its final size.
 * The screen stays awake while this composable is shown. [keepCase] encodes the parts as written, see
 * [QrMatrix.encode].
 *
 * Throws [com.google.zxing.WriterException] if a part does not fit in a QR code.
 */
@Composable
fun AnimatedQr(
    parts: List<String>,
    modifier: Modifier = Modifier,
    frameMillis: Long = 200,
    keepCase: Boolean = false,
) {
    var frames by remember(parts, keepCase) { mutableStateOf(QrFrameCache.get(parts, keepCase)) }
    LaunchedEffect(parts, keepCase) {
        if (frames == null) {
            val encoded = withContext(Dispatchers.Default) {
                parts.map { part -> QrMatrix.toPixels(QrMatrix.encode(part, keepCase)).toImageBitmap() }
            }
            QrFrameCache.put(parts, keepCase, encoded)
            frames = encoded
        }
    }
    var index by remember(parts) { mutableIntStateOf(0) }

    LaunchedEffect(parts, frameMillis) {
        if (parts.size > 1) {
            while (true) {
                delay(frameMillis)
                index = (index + 1) % parts.size
            }
        }
    }

    val view = LocalView.current
    DisposableEffect(view) {
        val wasOn = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = wasOn }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(CARD_CORNER))
                .background(CARD_PAPER)
                .padding(CARD_PADDING),
        ) {
            frames?.getOrNull(index)?.let { frame ->
                Image(
                    bitmap = frame,
                    contentDescription = "QR code ${index + 1} of ${parts.size}",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (parts.size > 1) {
            Spacer(Modifier.height(12.dp))
            PillChip(text = "Part ${index + 1} of ${parts.size}")
        }
    }
}

private fun QrPixels.toImageBitmap(): ImageBitmap =
    Bitmap.createBitmap(argb, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()

/**
 * The encoded frames of recently shown parts, least recently used first out, bounded by bytes: one animated code of a
 * dozen parts fits, and so do about fourteen single addresses. [LruCache] is synchronized, so any thread may use it.
 */
private object QrFrameCache {
    private const val MAX_BYTES = 8 shl 20

    private val frames = object : LruCache<Pair<List<String>, Boolean>, List<ImageBitmap>>(MAX_BYTES) {
        override fun sizeOf(key: Pair<List<String>, Boolean>, value: List<ImageBitmap>): Int =
            value.sumOf { it.width * it.height * 4 }
    }

    fun get(parts: List<String>, keepCase: Boolean): List<ImageBitmap>? = frames.get(parts to keepCase)

    fun put(parts: List<String>, keepCase: Boolean, encoded: List<ImageBitmap>) {
        frames.put(parts to keepCase, encoded)
    }
}
