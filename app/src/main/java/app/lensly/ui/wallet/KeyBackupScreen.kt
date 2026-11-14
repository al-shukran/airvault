package app.lensly.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.pressFeedback
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutlineInteractive
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing

/**
 * The write-down of a Single Key import, the last step before the wallet is saved. The key is shown once, in groups of
 * four, so it can be copied onto a backup by hand. Lensly keeps an encrypted copy, but that copy is not a backup. The
 * owner confirms that the copy exists, and only then can CREATE WALLET be pressed.
 */
@Composable
fun KeyBackupScreen(
    hex: String,
    copied: Boolean,
    onCopiedChange: (Boolean) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
) {
    LenslyScreen(
        title = "Key backup",
        modifier = modifier,
        bottomAction = {
            Column(verticalArrangement = Arrangement.spacedBy(LenslySpacing.sm)) {
                CopiedCheck(
                    checked = copied,
                    onCheckedChange = onCopiedChange,
                    label = "I copied the key onto my backup",
                )
                PillButton(
                    text = "Create wallet",
                    onClick = onCreate,
                    accent = true,
                    enabled = copied,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        Spacer(Modifier.height(LenslySpacing.lg))
        WalletEyebrow("Single key. Shown once")
        Spacer(Modifier.height(LenslySpacing.xs))
        DotHeadline(text = "WRITE DOWN")
        Spacer(Modifier.height(LenslySpacing.sm))
        InlineBanner(message = "Never photograph this key.", tone = BannerTone.Warning)
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = "This is the only time the key is shown. Lensly cannot make it again, and the encrypted copy on " +
                "this device is not a backup. Copy the key onto your backup now.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.lg))
        WalletPanel {
            WalletEyebrow("Private key")
            HashLines(hex = hex)
        }
        if (errorMessage != null) {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(message = errorMessage, tone = BannerTone.Error)
        }
        Spacer(Modifier.height(LenslySpacing.md))
    }
}

/**
 * The owner's explicit confirmation of a write-down, with [label] as its text. The row is one tap target, and the box
 * fills with a check mark when it is ticked. It is a checkbox for screen readers too.
 */
@Composable
internal fun CopiedCheck(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberKeyHaptics()
    val box = RoundedCornerShape(LenslyRadius.xs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressFeedback(interaction, enabled = true)
            .tapHaptic(haptics)
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = null,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = LenslySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .border(1.dp, if (checked) LenslyOnSurface else LenslyOutlineInteractive, box)
                .background(if (checked) LenslyOnSurface else Color.Transparent, box),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = LenslyBlack,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(LenslySpacing.sm))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
