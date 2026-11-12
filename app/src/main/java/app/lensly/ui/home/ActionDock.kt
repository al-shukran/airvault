package app.lensly.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
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

/** The test tag of the dock, so a test can measure where it sits. */
internal const val HOME_DOCK_TAG = "home-dock"

/** The test tags of the dock's four actions. */
internal const val HOME_DOCK_PAIR_TAG = "home-dock-pair"
internal const val HOME_DOCK_ACTIVITY_TAG = "home-dock-activity"
internal const val HOME_DOCK_WALLETS_TAG = "home-dock-wallets"
internal const val HOME_DOCK_SETTINGS_TAG = "home-dock-settings"

/** The height of the dock's bar. The content keeps clear of it, so the screen shares this constant. */
internal val DOCK_BAR_HEIGHT = 64.dp

/**
 * The action dock at the foot of Home: a floating bar of four equal actions, each an icon over its label. [onPair] opens the pairing
 * wizard, [onActivity] opens the Review Records of every wallet, [onWallets] opens the list of every wallet, and [onSettings] opens
 * Settings. Scanning is the large button in the content, so the dock does not repeat it.
 */
@Composable
internal fun ActionDock(
    onPair: () -> Unit,
    onWallets: () -> Unit,
    onActivity: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(DOCK_BAR_HEIGHT)
            .testTag(HOME_DOCK_TAG)
            .clip(LenslyPillShape)
            .background(LenslySurfaceContainer)
            .border(1.dp, LenslyOutline, LenslyPillShape)
            .padding(horizontal = LenslySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DockItem(
            icon = LenslyIcons.QrCode,
            label = "Pair",
            tag = HOME_DOCK_PAIR_TAG,
            onClick = onPair,
            modifier = Modifier.weight(1f),
        )
        DockItem(
            icon = LenslyIcons.History,
            label = "Activity",
            tag = HOME_DOCK_ACTIVITY_TAG,
            onClick = onActivity,
            modifier = Modifier.weight(1f),
        )
        DockItem(
            icon = LenslyIcons.Wallets,
            label = "Wallets",
            tag = HOME_DOCK_WALLETS_TAG,
            onClick = onWallets,
            modifier = Modifier.weight(1f),
        )
        DockItem(
            icon = SettingsGlyph,
            label = "Settings",
            tag = HOME_DOCK_SETTINGS_TAG,
            onClick = onSettings,
            modifier = Modifier.weight(1f),
        )
    }
}

/** One action of the dock: an icon over a small label. It dims while pressed and plays the key haptic. */
@Composable
private fun DockItem(icon: ImageVector, label: String, tag: String, onClick: () -> Unit, modifier: Modifier) {
    val haptics = rememberKeyHaptics()
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .testTag(tag)
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

/**
 * The settings glyph: a gear in outline with its hub, drawn at the same weight as the dock's other glyphs, so the four read as one
 * set. The material set has only the filled gear, which would look heavier than its neighbours.
 */
private val SettingsGlyph: ImageVector by lazy {
    val builder = ImageVector.Builder(
        name = "Settings",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    listOf(GEAR_PATH, HUB_PATH).forEach { data ->
        builder.addPath(
            pathData = addPathNodes(data),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    builder.build()
}

/** Eight teeth on a base circle of radius 7.4 and tips at 9.6, centred on (12, 12) in a 24 dp viewport. */
private const val GEAR_PATH =
    "M19.15 10.08 L21.48 10.5 L21.48 13.5 L19.15 13.92 L18.41 15.7 L19.77 17.64 L17.64 19.77 L15.7 18.41 " +
        "L13.92 19.15 L13.5 21.48 L10.5 21.48 L10.08 19.15 L8.3 18.41 L6.36 19.77 L4.23 17.64 L5.59 15.7 " +
        "L4.85 13.92 L2.52 13.5 L2.52 10.5 L4.85 10.08 L5.59 8.3 L4.23 6.36 L6.36 4.23 L8.3 5.59 L10.08 4.85 " +
        "L10.5 2.52 L13.5 2.52 L13.92 4.85 L15.7 5.59 L17.64 4.23 L19.77 6.36 L18.41 8.3 Z"

/** The hub of the gear: a circle of radius 3.2 at its centre. */
private const val HUB_PATH = "M8.8 12a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0"
