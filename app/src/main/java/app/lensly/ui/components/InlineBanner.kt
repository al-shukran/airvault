package app.lensly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LenslyError
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslyWarning

/** Severity of an [InlineBanner]. Each tone has its own icon and colour. */
enum class BannerTone { Info, Warning, Error }

/**
 * Inline message for a form or section: a tinted strip with a tone icon, an optional [title] and the [message]. It is a
 * polite live region, so TalkBack announces it when it appears.
 */
@Composable
fun InlineBanner(
    message: String,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val color = tone.color()
    val shape = RoundedCornerShape(LenslyRadius.md)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.5f), shape)
            .padding(LenslySpacing.sm)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = tone.icon(),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Column(
            modifier = Modifier.padding(start = LenslySpacing.xs),
            verticalArrangement = Arrangement.spacedBy(LenslySpacing.xxs),
        ) {
            if (title != null) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = LenslyOnSurface,
            )
        }
    }
}

private fun BannerTone.color(): Color = when (this) {
    BannerTone.Info -> LenslyOnSurface
    BannerTone.Warning -> LenslyWarning
    BannerTone.Error -> LenslyError
}

private fun BannerTone.icon(): ImageVector = when (this) {
    BannerTone.Info -> Icons.Filled.Info
    BannerTone.Warning -> Icons.Filled.Warning
    BannerTone.Error -> Icons.Filled.Close
}
