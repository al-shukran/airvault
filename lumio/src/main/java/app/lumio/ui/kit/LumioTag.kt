package app.lumio.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslyPillShape

/** A small capsule label, such as a count of tokens or a network. Its text is sentence case, in the label face. */
@Composable
fun LumioTag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    background: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    Text(
        text = text,
        modifier = modifier
            .clip(LenslyPillShape)
            .background(background)
            .padding(horizontal = LenslySpacing.sm, vertical = LenslySpacing.xxs),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
    )
}
