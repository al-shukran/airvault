package app.lensly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Fully rounded text button. Uses the surface-high fill, or the accent red when [accent] is true. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberKeyHaptics()
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(shape)
            .background(if (accent) colors.primary else colors.surfaceVariant)
            .tapHaptic(haptics)
            .clickable(role = Role.Button, onClick = onClick)
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
