package app.lensly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface

/**
 * Primary action: a white 80dp circle with a black icon. It shrinks and dims while pressed, plays the key haptic, and
 * fades when [enabled] is false.
 */
@Composable
fun PrimaryCircleButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberKeyHaptics()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(80.dp)
            .pressFeedback(interaction, enabled)
            .clip(CircleShape)
            .background(LenslyOnSurface)
            .tapHaptic(haptics, enabled)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = LenslyBlack,
            modifier = Modifier.size(32.dp),
        )
    }
}
