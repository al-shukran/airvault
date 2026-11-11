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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.pressFeedback
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutline
import app.lensly.ui.theme.LenslyPillShape
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceContainer

private val DOCK_HEIGHT = 88.dp
private val BAR_HEIGHT = 64.dp
private val SCAN_SIZE = 72.dp

/**
 * The action dock at the foot of Home: a floating bar with Pair at one end and Wallets at the other, and the Scan button in
 * the middle, which rises above the bar in the accent colour. [onPair] opens the pairing wizard, [onScan] starts a scan to sign,
 * and [onWallets] opens the list of every wallet.
 */
@Composable
internal fun ActionDock(
    onPair: () -> Unit,
    onScan: () -> Unit,
    onWallets: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().height(DOCK_HEIGHT)) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .clip(LenslyPillShape)
                .background(LenslySurfaceContainer)
                .border(1.dp, LenslyOutline, LenslyPillShape)
                .padding(horizontal = LenslySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockItem(icon = LenslyIcons.QrCode, label = "Pair", onClick = onPair, modifier = Modifier.weight(1f))
            // The gap the scan button rises into.
            Spacer(Modifier.width(SCAN_SIZE + LenslySpacing.md))
            DockItem(icon = LenslyIcons.Wallets, label = "Wallets", onClick = onWallets, modifier = Modifier.weight(1f))
        }
        ScanButton(onClick = onScan, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/** One end of the dock: an icon over a small label. It dims while pressed and plays the key haptic. */
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

/** The round scan button: the accent fill and a viewfinder glyph. It is the one accent action on Home. */
@Composable
private fun ScanButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberKeyHaptics()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(SCAN_SIZE)
            .pressFeedback(interaction, enabled = true)
            .clip(CircleShape)
            .background(LenslyAccent)
            .tapHaptic(haptics)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = "Scan a QR code" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = LenslyIcons.QrScan,
            contentDescription = null,
            tint = LenslyOnSurface,
            modifier = Modifier.size(32.dp),
        )
    }
}
