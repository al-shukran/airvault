package app.lensly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

enum class RoundKeyVariant { Normal, Accent, Outline }

/**
 * Dialer-style round key: a 72dp circle with a dot-matrix label and an optional
 * small muted sublabel underneath.
 */
@Composable
fun RoundKey(
    label: String,
    sublabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: RoundKeyVariant = RoundKeyVariant.Normal,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberKeyHaptics()
    val background: Color = when (variant) {
        RoundKeyVariant.Normal -> colors.surfaceVariant
        RoundKeyVariant.Accent -> colors.primary
        RoundKeyVariant.Outline -> Color.Transparent
    }
    val borderColor: Color? = if (variant == RoundKeyVariant.Outline) colors.outline else null

    Box(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(background)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, CircleShape) else Modifier)
            .tapHaptic(haptics)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onSurface,
            )
            if (sublabel != null) {
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
