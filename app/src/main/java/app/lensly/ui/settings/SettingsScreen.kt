package app.lensly.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lensly.core.wallet.BulkCap
import app.lensly.ui.components.DotProgress
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.pressFeedback
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletEyebrow

/** How many networks and tokens the catalog holds: the built-in entries plus any installed package. */
data class CatalogCounts(val networks: Int, val tokens: Int)

/**
 * The Settings screen of the vault: Entry, Bulk generation, Catalog and About, one row style throughout. LOCK sits under
 * the title, beside the offline label.
 *
 * [bulkCap] is null until the saved setting has been read, and its options are off until then. [onBulkCap] saves the
 * new cap. [onChangePassword] opens the App Password change, and [onWipe] opens the wipe. [catalog] is null while the
 * counts are read. The hidden entry gesture is read-only, because the detector has one fixed gesture.
 */
@Composable
fun SettingsScreen(
    bulkCap: BulkCap?,
    onBulkCap: (BulkCap) -> Unit,
    onChangePassword: () -> Unit,
    catalog: CatalogCounts?,
    onUpdateCatalog: () -> Unit,
    onWipe: () -> Unit,
    versionName: String,
    versionCode: Int,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(title = "Settings", modifier = modifier, onBack = onBack) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WalletEyebrow("Offline vault", modifier = Modifier.weight(1f))
            PillButton(text = "Lock", onClick = onLock)
        }

        SettingsSection("Entry") {
            SettingsRow(
                title = "Hidden entry gesture",
                description = "Two-finger double-tap on the viewfinder, then hold both fingers.\n" +
                    "This version has one gesture. Choosing another is not available yet.",
            )
            SettingsRow(
                title = "Change App Password",
                description = "Enter the current password, then the new one twice. Your wallets stay as they are.",
                onClick = onChangePassword,
            )
        }

        SettingsSection("Bulk generation") {
            BulkCapItem(bulkCap = bulkCap, onBulkCap = onBulkCap)
        }

        SettingsSection("Catalog") {
            SettingsRow(
                title = "Update catalog",
                description = "Install a signed package from removable storage. It adds networks and tokens.",
                onClick = onUpdateCatalog,
            )
            SettingsRow(
                title = "Networks and tokens",
                description = catalog?.let { "${it.networks} networks · ${it.tokens} tokens" }
                    ?: "Reading the catalog…",
                loading = catalog == null,
            )
        }

        SettingsSection("Wipe") {
            SettingsRow(
                title = "Wipe the vault",
                description = "Erase every wallet and the App Password from this device. A new App Password is set up next.",
                onClick = onWipe,
            )
        }

        SettingsSection("About") {
            SettingsRow(title = "Version", description = "$versionName ($versionCode)")
            SettingsRow(title = "License", description = "Apache License 2.0. The text is in the LICENSE file.")
            SettingsRow(title = "Third-party notices", description = NOTICES)
        }
        Spacer(Modifier.height(LenslySpacing.xl))
    }
}

/** One section: a muted label, then its rows. Every section starts the same distance below the one before. */
@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.xl)) {
        WalletEyebrow(title)
        Spacer(Modifier.height(LenslySpacing.xs))
        content()
    }
}

/** Wraps a settings row or block with the one divider that closes it. */
@Composable
private fun SettingsItem(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        content()
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * The one row style of Settings: a title, an optional description, and a trailing mark. A row with [onClick] shows an
 * arrow and presses like a button. A [loading] row shows a progress mark in place of the arrow.
 */
@Composable
private fun SettingsRow(
    title: String,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    loading: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberKeyHaptics()
    val tapModifier = if (onClick != null) {
        Modifier
            .pressFeedback(interaction, enabled = true)
            .tapHaptic(haptics)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    SettingsItem {
        Row(
            modifier = Modifier.fillMaxWidth().then(tapModifier).padding(vertical = LenslySpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = LenslyOnSurface,
                )
                if (description != null) {
                    Spacer(Modifier.height(LenslySpacing.xxs))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (loading) {
                Spacer(Modifier.width(LenslySpacing.sm))
                DotProgress(description = "Loading $title")
            } else if (onClick != null) {
                Spacer(Modifier.width(LenslySpacing.sm))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The Bulk Cap: one pill per cap the owner may select, the saved one filled. Until the saved cap is read the pills are
 * off.
 */
@Composable
private fun BulkCapItem(bulkCap: BulkCap?, onBulkCap: (BulkCap) -> Unit) {
    SettingsItem {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.md),
            verticalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
        ) {
            Text(
                text = "Bulk Cap",
                style = MaterialTheme.typography.titleMedium,
                color = LenslyOnSurface,
            )
            Text(
                text = "The most accounts one bulk run can create. A run above it is refused.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
            ) {
                for (cap in BulkCap.entries) {
                    PillButton(
                        text = cap.label,
                        onClick = { onBulkCap(cap) },
                        modifier = Modifier.weight(1f).semantics { selected = cap == bulkCap },
                        accent = cap == bulkCap,
                        enabled = bulkCap != null,
                    )
                }
            }
            if (bulkCap == null) {
                Text(
                    text = "Reading the saved cap…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val NOTICES = "Chain and token logos: web3icons (MIT) and Iconify token-icons (MIT).\n" +
    "Logo attribution files are in tools/icons/.\n" +
    "Fonts: Doto and Space Mono, SIL Open Font License 1.1. The texts are in licenses/.\n" +
    "Libraries: Bouncy Castle (Bouncy Castle Licence), zxcvbn (MIT License), and web3j, ZXing and " +
    "Hummingbird (Apache License 2.0)."
