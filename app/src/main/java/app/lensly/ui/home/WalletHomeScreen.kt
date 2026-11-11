package app.lensly.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.pressFeedback
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WALLET_GUTTER
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillSize
import app.lensly.ui.wallet.WalletPillStyle

/**
 * The wallet list: a dot-matrix headline, a count, one row per wallet, and a red add button. When there are no wallets
 * it invites the owner to add one. [onOpen] receives the wallet id. [onSettings] opens the Settings screen. [onLock]
 * locks the vault and leaves the hidden area. [onUpdateCatalog] opens the import of a signed registry package.
 * [debugNotice], when set, shows an inline warning, for example that the keystore is software-only.
 */
@Composable
fun WalletHomeScreen(
    wallets: List<WalletMeta>,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit,
    onUpdateCatalog: () -> Unit,
    modifier: Modifier = Modifier,
    debugNotice: String? = null,
) {
    val haptics = rememberKeyHaptics()
    Box(modifier = modifier.fillMaxSize().background(LenslyBlack)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = WALLET_GUTTER),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = LenslySpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WalletEyebrow("Offline vault")
                Spacer(Modifier.weight(1f))
                WalletPill(
                    text = "Settings",
                    onClick = onSettings,
                    modifier = Modifier.tapHaptic(haptics),
                    style = WalletPillStyle.Outline,
                    size = WalletPillSize.Compact,
                )
                Spacer(Modifier.width(LenslySpacing.xs))
                WalletPill(
                    text = "Lock",
                    onClick = onLock,
                    modifier = Modifier.tapHaptic(haptics),
                    style = WalletPillStyle.Outline,
                    size = WalletPillSize.Compact,
                )
            }
            Spacer(Modifier.height(LenslySpacing.sm))
            DotHeadline(text = "WALLETS")
            if (debugNotice != null) {
                Spacer(Modifier.height(LenslySpacing.sm))
                InlineBanner(message = debugNotice, tone = BannerTone.Warning)
            }
            Spacer(Modifier.height(LenslySpacing.md))
            if (wallets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Filled.Lock,
                        title = "No wallets yet",
                        body = "Create a wallet, or restore one from its 24 words.",
                        actionLabel = "Add wallet",
                        onAction = onAdd,
                    )
                }
            } else {
                WalletEyebrow(if (wallets.size == 1) "1 wallet" else "${wallets.size} wallets")
                Spacer(Modifier.height(LenslySpacing.xs))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    // Room under the last row, so the add button never covers it.
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    items(wallets, key = { it.id }) { wallet ->
                        WalletRow(wallet = wallet, onClick = { onOpen(wallet.id) })
                    }
                }
            }
        }
        WalletPill(
            text = "Update catalog",
            onClick = onUpdateCatalog,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = WALLET_GUTTER, bottom = LenslySpacing.md)
                .tapHaptic(haptics),
            style = WalletPillStyle.Outline,
            size = WalletPillSize.Compact,
        )
        AddWalletButton(
            onClick = onAdd,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(end = LenslySpacing.md, bottom = LenslySpacing.md),
        )
    }
}

/**
 * One wallet in the list: its name, its kind as chips, and a chevron. The whole row is the tap target, and it shows the
 * standard ripple. No haptic here, because a press that starts a scroll should not buzz.
 */
@Composable
private fun WalletRow(wallet: WalletMeta, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = LenslySpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = wallet.name.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = LenslyOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(LenslySpacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
                    if (wallet.deviceGenerated) PillChip(text = "Device-generated")
                    if (wallet.kind == WalletKind.SINGLE_KEY) {
                        PillChip(text = "Single key")
                    } else {
                        PillChip(text = "Master ${wallet.masterFingerprintHex.orEmpty().uppercase()}")
                    }
                }
            }
            Spacer(Modifier.width(LenslySpacing.sm))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * The red circular add button from the contacts reference. It shrinks and dims while pressed and plays the key haptic,
 * like the shared buttons.
 */
@Composable
private fun AddWalletButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberKeyHaptics()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(64.dp)
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
            .semantics { contentDescription = "Add wallet" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = LenslyOnSurface,
            modifier = Modifier.size(28.dp),
        )
    }
}
