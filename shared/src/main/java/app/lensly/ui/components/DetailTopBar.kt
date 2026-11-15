package app.lensly.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing

/**
 * The top bar of a wallet screen or an App screen: Back, Lock, and the options icon, which [optionsLabel] names for a reader of
 * the screen. A null [onOptions] leaves the options icon out.
 */
@Composable
fun DetailTopBar(
    onBack: () -> Unit,
    onLock: () -> Unit,
    onOptions: (() -> Unit)? = null,
    optionsLabel: String = "Options",
) {
    val haptics = rememberKeyHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = LenslySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.tapHaptic(haptics)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = LenslyOnSurface)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onLock, modifier = Modifier.tapHaptic(haptics)) {
            Icon(Icons.Filled.Lock, contentDescription = "Lock", tint = LenslyOnSurface)
        }
        if (onOptions != null) {
            IconButton(onClick = onOptions, modifier = Modifier.tapHaptic(haptics)) {
                Icon(Icons.Filled.MoreVert, contentDescription = optionsLabel, tint = LenslyOnSurface)
            }
        }
    }
}
