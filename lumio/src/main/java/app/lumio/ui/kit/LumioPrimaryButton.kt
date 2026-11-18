package app.lumio.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LenslyPillShape

/** The height of the primary button (section 1.3). */
private val BUTTON_HEIGHT = 56.dp

/** How far a pressed primary button shrinks (section 3.4). */
private const val PRESSED_BUTTON_SCALE = 0.97f

/**
 * The one filled action of a screen (control rule 1): a full-width gold capsule. Disabled, it sits on the raised surface with muted text, and the
 * reason is said beside it, not in it. It scales to 0.97 while pressed, and a tap gives a light tick (section 3.5).
 */
@Composable
fun LumioPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val haptics = rememberLumioHaptics()
    val container = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(BUTTON_HEIGHT)
            .lumioPress(
                scale = PRESSED_BUTTON_SCALE,
                enabled = enabled,
                shape = LenslyPillShape,
                onClick = {
                    haptics.tick()
                    onClick()
                },
            )
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}
