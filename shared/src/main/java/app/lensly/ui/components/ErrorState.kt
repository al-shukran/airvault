package app.lensly.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.lensly.ui.theme.LenslyError

/**
 * Centred error state for a screen or action that failed: an [icon] in the error colour, a [title] that says what went
 * wrong, an optional [body] on what to do next, and an optional action such as RETRY.
 */
@Composable
fun ErrorState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    StateBlock(
        icon = icon,
        iconTint = LenslyError,
        title = title,
        modifier = modifier,
        body = body,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}
