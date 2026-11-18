package app.lensly.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LocalUppercaseLabels

/**
 * The lock action of the unlocked vault. The cover sets it for the Hidden Area, so every detail screen there shows the lock icon.
 * The camera's gallery sets it to null, because it opens while the vault is locked. A null value shows no lock icon.
 */
val LocalVaultLock = staticCompositionLocalOf<(() -> Unit)?> { null }

/** The height of the top bar. The back button is 48 dp and sits centred in it. */
private val TOP_BAR_HEIGHT = 56.dp

/**
 * The one top bar of every detail screen: Back on the left when [onBack] is set, the [title] in the dot-matrix headline face, the lock
 * icon on the right while the vault is unlocked (see [LocalVaultLock]), and the options icon after it, which [optionsLabel] names for a
 * reader of the screen. A null [onOptions] leaves the options icon out, and a null [title] leaves the title out.
 */
@Composable
fun DetailTopBar(
    title: String?,
    onBack: (() -> Unit)?,
    onOptions: (() -> Unit)? = null,
    optionsLabel: String = "Options",
    optionsIcon: ImageVector = Icons.Filled.MoreVert,
) {
    val haptics = rememberKeyHaptics()
    val onLock = LocalVaultLock.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT)
            .padding(horizontal = LenslySpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.tapHaptic(haptics)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            // Keeps the title on the page gutter when there is no back button.
            Spacer(Modifier.width(LenslySpacing.md - LenslySpacing.xxs))
        }
        if (title != null) {
            Text(
                text = if (LocalUppercaseLabels.current) title.uppercase() else title,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (onLock != null) {
            IconButton(onClick = onLock, modifier = Modifier.tapHaptic(haptics)) {
                Icon(Icons.Filled.Lock, contentDescription = "Lock the vault", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (onOptions != null) {
            IconButton(onClick = onOptions, modifier = Modifier.tapHaptic(haptics)) {
                Icon(optionsIcon, contentDescription = optionsLabel, tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
