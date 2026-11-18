package app.lensly.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LenslySpacing

/** The height of a row with one line. Two lines with an amount take 72 dp. */
private val ROW_HEIGHT = 64.dp

/** How far a pressed row shrinks (section 3.4). */
private const val PRESSED_ROW_SCALE = 0.98f

/**
 * A row of an island: an optional [leading] mark, a [title] with a [description] under it when given, the [trailing] content (an amount or a tag), and a
 * chevron when the row opens something ([onClick]). A long press calls [onLongClick] when it is set. The row scales to 0.98 while it is pressed,
 * and it has the default ripple. It is a button for accessibility when it is tappable. [titleModifier] is laid on the title, so a screen can move the
 * title to the next screen.
 */
@Composable
fun LumioRow(
    title: String,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    description: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ROW_HEIGHT)
            .then(
                if (onClick != null) {
                    Modifier.lumioPress(scale = PRESSED_ROW_SCALE, onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(LenslySpacing.sm))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                modifier = titleModifier,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(LenslySpacing.sm))
            trailing()
        }
        if (onClick != null) {
            Spacer(Modifier.width(LenslySpacing.xs))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
