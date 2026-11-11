package app.lensly.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.pressFeedback
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutline
import app.lensly.ui.theme.LenslyPillShape
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceContainer

private val BAR_HEIGHT = 64.dp

/**
 * The action dock at the foot of Home: a floating bar with Pair, Activity and Wallets. Scan is not here, because Home's large
 * "Scan to sign" button is the one scan action. [onPair] opens the pairing wizard, [onActivity] opens the Review Records of every
 * wallet, and [onWallets] opens the list of every wallet.
 */
@Composable
internal fun ActionDock(
    onPair: () -> Unit,
    onWallets: () -> Unit,
    onActivity: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .clip(LenslyPillShape)
            .background(LenslySurfaceContainer)
            .border(1.dp, LenslyOutline, LenslyPillShape)
            .padding(horizontal = LenslySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DockItem(icon = LenslyIcons.QrCode, label = "Pair", onClick = onPair, modifier = Modifier.weight(1f))
        DockItem(
            icon = LenslyIcons.History,
            label = "Activity",
            onClick = onActivity,
            modifier = Modifier.weight(1f),
        )
        DockItem(icon = LenslyIcons.Wallets, label = "Wallets", onClick = onWallets, modifier = Modifier.weight(1f))
    }
}

/** One item of the dock: an icon over a small label. It dims while pressed and plays the key haptic. */
@Composable
private fun DockItem(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    val haptics = rememberKeyHaptics()
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .pressFeedback(interaction, enabled = true)
            .tapHaptic(haptics)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = LenslyOnSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(LenslySpacing.xxs))
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
