package app.lensly.ui.qr

import android.graphics.Bitmap
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
import kotlinx.coroutines.delay

private val CARD_CORNER = 24.dp
private val CARD_PADDING = 16.dp
private val CARD_PAPER = Color.White

/**
 * Shows one or more QR codes as a black-on-white card, the layout scanners expect.
 *
 * With more than one part, the parts cycle every [frameMillis] and a "PART 3 OF 12" chip sits
 * under the card. Each part is encoded and rendered once per [parts] value and kept in memory.
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
    val frames: List<ImageBitmap> = remember(parts, keepCase) {
        parts.map { part -> QrMatrix.toPixels(QrMatrix.encode(part, keepCase)).toImageBitmap() }
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
            val frame = frames.getOrNull(index)
            if (frame != null) {
                Image(
                    bitmap = frame,
                    contentDescription = "QR code ${index + 1} of ${frames.size}",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (frames.size > 1) {
            Spacer(Modifier.height(12.dp))
            PillChip(text = "Part ${index + 1} of ${frames.size}")
        }
    }
}

private fun QrPixels.toImageBitmap(): ImageBitmap =
    Bitmap.createBitmap(argb, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
