package app.lumio.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.LocalLumenPalette

/** How far a pressed text action shrinks: the same 0.97 as the primary button. */
private const val PRESSED_TEXT_SCALE = 0.97f

/**
 * The one secondary action of a screen (control rule 1): a text button in the accent ink, with the size of a Material text button. Nothing else may
 * look like a button. It scales to 0.97 while pressed.
 */
@Composable
fun LumioTextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = ButtonDefaults.MinWidth, minHeight = ButtonDefaults.MinHeight)
            .lumioPress(scale = PRESSED_TEXT_SCALE, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(ButtonDefaults.TextButtonContentPadding),
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) LocalLumenPalette.current.accentInk else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA),
            maxLines = 1,
        )
    }
}

/** The alpha of disabled content, as Material's text buttons draw it. */
private const val DISABLED_ALPHA = 0.38f
