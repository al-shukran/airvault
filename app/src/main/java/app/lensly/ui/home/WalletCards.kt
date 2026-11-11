package app.lensly.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.app.visualIdentity
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.WalletArt
import app.lensly.ui.components.pressFeedback
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.profile.ProfileAvatar
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutline
import app.lensly.ui.theme.LenslyOutlineInteractive
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceContainer

/** The test tag of every wallet card, so a test can count the cards on screen. */
internal const val WALLET_CARD_TAG = "wallet-card"

/** The test tag of the add tile. */
internal const val ADD_WALLET_TAG = "add-wallet"

private val CARD_HEIGHT = 150.dp
private val COMPACT_CARD_HEIGHT = 104.dp
private val CARD_SHAPE = RoundedCornerShape(LenslyRadius.xl)

/**
 * One wallet as a card: its generated [WalletArt] under a scrim, the type chip and the avatar on top, then the name and the
 * accounts and master fingerprint in mono. [position] is the wallet's place in its list, which sets the avatar when the wallet
 * has no look of its own. A tap calls [onClick]. A long press calls [onLongPress], when set. [compact] makes a short card for
 * the Wallets list and the pairing picker.
 */
@Composable
internal fun WalletCard(
    wallet: WalletMeta,
    profile: WalletProfile,
    position: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    compact: Boolean = false,
    showFingerprint: Boolean = true,
) {
    val haptics = rememberKeyHaptics()
    val feedback = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val identity = visualIdentity(profile.colour, profile.icon, position)
    val accounts = if (wallet.accountCount == 1) "1 account" else "${wallet.accountCount} accounts"
    val fingerprint = if (showFingerprint) wallet.masterFingerprintHex?.let { "master $it" } else null
    val details = listOfNotNull(accounts, fingerprint).joinToString(" · ").uppercase()
    val longClick: (() -> Unit)? = onLongPress?.let { open ->
        {
            feedback.performHapticFeedback(HapticFeedbackType.LongPress)
            open()
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (compact) COMPACT_CARD_HEIGHT else CARD_HEIGHT)
            .testTag(WALLET_CARD_TAG)
            .pressFeedback(interaction, enabled = true)
            .clip(CARD_SHAPE)
            .border(1.dp, LenslyOutline, CARD_SHAPE)
            .tapHaptic(haptics)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
                onLongClick = longClick,
            ),
    ) {
        WalletArt(seed = wallet.id, colour = profile.colour, modifier = Modifier.matchParentSize())
        // The scrim keeps the text legible over the brightest glow.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(listOf(LenslyBlack.copy(alpha = 0f), LenslyBlack.copy(alpha = 0.8f)))),
        )
        Column(modifier = Modifier.fillMaxSize().padding(if (compact) 14.dp else 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillChip(text = if (wallet.kind == WalletKind.SINGLE_KEY) "Single key" else "Mnemonic")
                Spacer(Modifier.weight(1f))
                ProfileAvatar(identity = identity, size = if (compact) 24.dp else 28.dp)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = wallet.name.uppercase(),
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                color = LenslyOnSurface,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(LenslySpacing.xxs))
            Text(
                text = details,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The dashed add tile that ends a list of wallets: a plus and the words "Add wallet". It is a tile rather than a floating
 * button, so it sits where a new wallet would go.
 */
@Composable
internal fun AddWalletTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberKeyHaptics()
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .testTag(ADD_WALLET_TAG)
            .pressFeedback(interaction, enabled = true)
            .drawBehind {
                drawRoundRect(
                    color = LenslyOutlineInteractive,
                    cornerRadius = CornerRadius(LenslyRadius.xl.toPx()),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())),
                    ),
                )
            }
            .tapHaptic(haptics)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = LenslySpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = LenslyOnSurface,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(LenslySpacing.sm))
        Text(
            text = "Add wallet",
            style = MaterialTheme.typography.titleSmall,
            color = LenslyOnSurface,
        )
    }
}

/**
 * The context sheet of a wallet, opened by a long press on its card: Open, Edit wallet profile and Pair this wallet. Each
 * action closes the sheet first, then calls its callback with the wallet's id.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalletContextSheet(
    wallet: WalletMeta,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onPair: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LenslySurfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = LenslySpacing.md, end = LenslySpacing.md, bottom = LenslySpacing.lg),
        ) {
            Text(
                text = wallet.name.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = LenslyOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(LenslySpacing.sm))
            SheetAction(icon = Icons.AutoMirrored.Filled.ArrowForward, label = "Open") {
                onDismiss()
                onOpen(wallet.id)
            }
            SheetAction(icon = Icons.Filled.Edit, label = "Edit wallet profile") {
                onDismiss()
                onEdit(wallet.id)
            }
            SheetAction(icon = LenslyIcons.QrCode, label = "Pair this wallet") {
                onDismiss()
                onPair(wallet.id)
            }
        }
    }
}

/** One row of the context sheet: its icon, its label, and a tap that runs [onClick] and the key haptic. */
@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val haptics = rememberKeyHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .tapHaptic(haptics)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = LenslyOnSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(LenslySpacing.md))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = LenslyOnSurface)
    }
}
