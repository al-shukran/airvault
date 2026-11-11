package app.lensly.ui.qr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceHigh
import kotlin.math.roundToInt

/**
 * Scan screen: the title, the live [QrScanner] in a rounded viewport, an optional progress readout for multi-part UR
 * payloads, a status banner, and a CANCEL pill pinned at the bottom.
 *
 * [progress] is a fraction from 0 to 1, or null when the payload is a single code. [statusText] is free text, such as
 * the hint, the last decoded payload, or why a code was not accepted. It is announced to TalkBack when it changes.
 */
@Composable
fun ScanScreen(
    title: String,
    progress: Float?,
    statusText: String,
    onText: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScanFrame(
        title = title,
        progress = progress,
        statusText = statusText,
        onCancel = onCancel,
        modifier = modifier,
    ) { viewportModifier ->
        QrScanner(onText = onText, modifier = viewportModifier)
    }
}

/**
 * The scan layout around a viewport. [ScanScreen] puts the live scanner in it. The screenshot tests put a placeholder
 * in it, because a camera feed cannot be rendered off the device.
 */
@Composable
internal fun ScanFrame(
    title: String,
    progress: Float?,
    statusText: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewport: @Composable (Modifier) -> Unit,
) {
    LenslyScreen(
        title = title,
        modifier = modifier,
        scrollable = false,
        bottomAction = {
            PillButton(
                text = "Cancel",
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        viewport(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(LenslyRadius.xl)),
        )
        Spacer(Modifier.height(LenslySpacing.md))
        if (progress != null) {
            ScanProgress(progress = progress)
            Spacer(Modifier.height(LenslySpacing.sm))
        }
        InlineBanner(message = statusText, tone = BannerTone.Info)
    }
}

/** A thin white bar over a dark track, with the percentage above it. It reports its fraction to accessibility services. */
@Composable
private fun ScanProgress(progress: Float) {
    val fraction = progress.coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "SCANNING",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${(fraction * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(LenslySpacing.xs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(LenslySurfaceHigh)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(LenslyOnSurface),
            )
        }
    }
}
