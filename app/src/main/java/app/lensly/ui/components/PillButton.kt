package app.lensly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LenslyPillShape

/**
 * Fully rounded text button on the surface-high fill, or the accent red when [accent] is true. It shrinks and dims while
 * pressed and plays the key haptic. When [enabled] is false it dims and ignores taps.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberKeyHaptics()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(56.dp)
            .pressFeedback(interaction, enabled)
            .clip(LenslyPillShape)
            .background(if (accent) colors.primary else colors.surfaceVariant)
            .tapHaptic(haptics, enabled)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurface,
        )
    }
}
