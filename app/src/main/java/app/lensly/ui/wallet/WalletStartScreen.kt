package app.lensly.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.pressFeedback
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceContainer

/**
 * First screen of the wallet flow: create a new wallet, restore one from its 24 words, or import a single private key.
 * The caller decides what each choice starts. Create is the primary choice, so it is the white row.
 */
@Composable
fun WalletStartScreen(
    onCreate: () -> Unit,
    onRestore: () -> Unit,
    onImportKey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(
        title = "Wallet",
        modifier = modifier,
        bottomAction = {
            Text(
                text = "Nothing leaves this device.",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        },
    ) {
        WalletEyebrow("Offline vault")
        Spacer(Modifier.height(LenslySpacing.xs))
        DotHeadline(text = "NEW WALLET")
        Spacer(Modifier.height(LenslySpacing.lg))
        ChoiceRow(
            title = "Create",
            description = "Make a new 24-word recovery phrase on this device. You can add your own dice or coin rolls.",
            onClick = onCreate,
            primary = true,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        ChoiceRow(
            title = "Restore",
            description = "Type the 24 words you already have.",
            onClick = onRestore,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        ChoiceRow(
            title = "Import private key",
            description = "Type one key of 64 hex digits, with or without 0x.",
            onClick = onImportKey,
        )
    }
}

/**
 * A full-width choice: a title and a line on what it does, with an arrow. It shrinks and dims while pressed and plays
 * the key haptic, as the shared buttons do.
 */
@Composable
private fun ChoiceRow(
    title: String,
    description: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    val haptics = rememberKeyHaptics()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(LenslyRadius.lg)
    val ink = if (primary) LenslyBlack else LenslyOnSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressFeedback(interaction, enabled = true)
            .clip(shape)
            .background(if (primary) LenslyOnSurface else LenslySurfaceContainer, shape)
            .tapHaptic(haptics)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(LenslySpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(LenslySpacing.xxs),
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = ink,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = if (primary) LenslyBlack else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(LenslySpacing.sm))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = ink,
        )
    }
}
