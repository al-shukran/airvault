package app.lensly.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.app.visualIdentity
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.LumioRow
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.WalletArt
import app.lensly.ui.components.pressFeedback
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.profile.ProfileAvatar
import app.lensly.ui.theme.LenslySpacing

/** The test tag of every wallet card, so a test can count the cards on screen. */
internal const val WALLET_CARD_TAG = "wallet-card"

/** The test tag of the add tile. */
internal const val ADD_WALLET_TAG = "add-wallet"

private val CARD_HEIGHT = 150.dp
private val COMPACT_CARD_HEIGHT = 104.dp

/** A compact card whose name takes two lines, as the Home carousel's does. The height holds the name, the chip and the line under it. */
private val COMPACT_TWO_LINE_CARD_HEIGHT = 120.dp

/**
 * One wallet as a card: its generated [WalletArt] under a scrim, the type chip and the avatar on top, then the name and the
 * accounts and master fingerprint in mono. [position] is the wallet's place in its list, which sets the avatar when the wallet
 * has no look of its own. A tap calls [onClick]. A long press calls [onLongPress], when set. [compact] makes a short card for
 * the Wallets list and the pairing picker. A compact card has its name on one line, unless [nameLines] asks for two, which the Home
 * carousel does so a name is not cut mid-phrase.
 */
@Composable
internal fun WalletCard(
    wallet: WalletMeta,
    profile: WalletProfile,
    accounts: Int,
    position: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    compact: Boolean = false,
    showFingerprint: Boolean = true,
    nameLines: Int = 1,
) {
    val haptics = rememberKeyHaptics()
    val feedback = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val identity = visualIdentity(profile.colour, profile.icon, position)
    val fingerprint = if (showFingerprint) wallet.masterFingerprintHex?.let { "master ${it.uppercase()}" } else null
    val details = listOfNotNull(accountCountLabel(accounts), fingerprint).joinToString(" · ")
    val longClick: (() -> Unit)? = onLongPress?.let { open ->
        {
            feedback.performHapticFeedback(HapticFeedbackType.LongPress)
            open()
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(
                if (!compact) CARD_HEIGHT else if (nameLines > 1) COMPACT_TWO_LINE_CARD_HEIGHT else COMPACT_CARD_HEIGHT,
            )
            .testTag(WALLET_CARD_TAG)
            .pressFeedback(interaction, enabled = true)
            .clip(MaterialTheme.shapes.extraLarge)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraLarge)
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
                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background.copy(alpha = 0f), MaterialTheme.colorScheme.background.copy(alpha = 0.8f)))),
        )
        Column(modifier = Modifier.fillMaxSize().padding(if (compact) 14.dp else 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillChip(text = if (wallet.kind == WalletKind.SINGLE_KEY) "Single Key" else "Mnemonic")
                Spacer(Modifier.weight(1f))
                ProfileAvatar(identity = identity, size = if (compact) 24.dp else 28.dp)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = wallet.name,
                style = when {
                    !compact -> MaterialTheme.typography.headlineSmall
                    // Twelve sp in mono, so two lines hold the whole name of a carousel card.
                    nameLines > 1 -> MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp, lineHeight = 16.sp)
                    else -> MaterialTheme.typography.titleMedium
                },
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (compact) nameLines else 2,
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
 * One wallet as a row of an island, on the Home wallet list and the Wallets list: its avatar, its name, and under the name its accounts, a
 * Single Key label when it is one, and its master fingerprint when [showFingerprint] is set. A tap calls [onClick]; a long press calls
 * [onLongPress] when it is set. The row carries the card's test tag, so a test counts the wallets on screen the same way.
 */
@Composable
internal fun WalletRow(
    wallet: WalletMeta,
    profile: WalletProfile,
    accounts: Int,
    position: Int,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    showFingerprint: Boolean,
) {
    val haptics = rememberKeyHaptics()
    val feedback = LocalHapticFeedback.current
    val identity = visualIdentity(profile.colour, profile.icon, position)
    val kind = if (wallet.kind == WalletKind.SINGLE_KEY) "Single key" else null
    val fingerprint = if (showFingerprint) wallet.masterFingerprintHex?.let { "master ${it.uppercase()}" } else null
    val details = listOfNotNull(kind, accountCountLabel(accounts), fingerprint).joinToString(" · ")
    LumioRow(
        title = wallet.name,
        description = details,
        leading = { ProfileAvatar(identity = identity, size = 40.dp) },
        onClick = onClick,
        onLongClick = onLongPress?.let { open ->
            {
                feedback.performHapticFeedback(HapticFeedbackType.LongPress)
                open()
            }
        },
        modifier = Modifier.testTag(WALLET_CARD_TAG).tapHaptic(haptics),
    )
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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = LenslySpacing.md, end = LenslySpacing.md, bottom = LenslySpacing.lg),
        ) {
            Text(
                text = wallet.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(LenslySpacing.sm))
            SheetAction(icon = Icons.AutoMirrored.Filled.ArrowForward, label = "Open") {
                onDismiss()
                onOpen(wallet.id)
            }
            SheetAction(icon = Icons.Filled.Edit, label = "Edit Wallet Profile") {
                onDismiss()
                onEdit(wallet.id)
            }
            SheetAction(icon = LenslyIcons.QrCode, label = "Pair This Wallet") {
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
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(LenslySpacing.md))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
