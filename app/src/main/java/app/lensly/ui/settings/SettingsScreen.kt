package app.lensly.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.core.registry.DenylistEntry
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.ProxyPolicy
import app.lensly.core.registry.TokenEntry
import app.lensly.core.registry.WalletEntry
import app.lensly.core.registry.WhitelistEntry
import app.lensly.core.wallet.BulkCap
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.components.ChainIcon
import app.lensly.ui.components.DotProgress
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.TokenIcon
import app.lensly.ui.components.pressFeedback
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletEyebrow

/** The networks, tokens and approved contracts the catalog holds: the built-in entries plus any installed package. */
data class Catalog(
    val networks: List<NetworkEntry>,
    val tokens: List<TokenEntry>,
    val whitelist: List<WhitelistEntry> = emptyList(),
    val denylist: List<DenylistEntry> = emptyList(),
    val wallets: List<WalletEntry> = emptyList(),
    /** True when an installed package is present but failed verification, so the built-in whitelist is in force. */
    val installedUpdateUnverified: Boolean = false,
)

/** The count with its noun in the right form, such as "1 network" or "12 networks". */
private fun Int.countOf(one: String, many: String): String = "$this ${if (this == 1) one else many}"

/** The size of an icon in a catalog row's preview, and how many a preview shows. */
private val PREVIEW_ICON_SIZE: Dp = 28.dp
private const val PREVIEW_ICONS = 4

/**
 * The Settings screen of the vault: Entry, Bulk generation, the wrong password lockout, the catalog and About, one row style
 * throughout. LOCK sits under the title, beside the offline label.
 *
 * [bulkCap] is null until the saved setting has been read, and its options are off until then. [onBulkCap] saves the
 * new cap. [onChangePassword] opens the App Password change, and [onWipe] opens the wipe. [failureDelayCap] is the saved
 * wrong password lockout, null while it is read, and [onFailureDelayCap] opens the screen that changes it. [catalog] is null
 * while it is read, and [onNetworks] and [onTokens] open the lists. [entryGesture] is the gesture the camera uses now, and
 * [onEntryGesture] opens the choice of another. [onApprovedContracts] opens the approved contracts, which [catalog] counts.
 * [proxyPolicy] is the policy in force, null while it is read, and [onProxyPolicy] opens the screen that changes it.
 */
@Composable
fun SettingsScreen(
    bulkCap: BulkCap?,
    onBulkCap: (BulkCap) -> Unit,
    onChangePassword: () -> Unit,
    entryGesture: EntryGesture = EntryGesture.DEFAULT,
    onEntryGesture: () -> Unit = {},
    failureDelayCap: FailureCapChoice?,
    onFailureDelayCap: () -> Unit,
    catalog: Catalog?,
    onNetworks: () -> Unit,
    onTokens: () -> Unit,
    onApprovedContracts: () -> Unit = {},
    proxyPolicy: ProxyPolicy? = null,
    onProxyPolicy: () -> Unit = {},
    onUpdateCatalog: () -> Unit,
    onWipe: () -> Unit,
    onDuressPassword: () -> Unit = {},
    onRemoveDuressPassword: () -> Unit = {},
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
                description = entryGesture.title,
                onClick = onEntryGesture,
            )
            SettingsRow(
                title = "Change App Password",
                description = "Enter the current password, then the new one twice. Your wallets stay as they are.",
                onClick = onChangePassword,
            )
            // The same two rows show in the real vault and in a decoy, so the decoy's Settings look identical.
            SettingsRow(
                title = "Duress Password",
                description = "A second password. Enter the App Password, then the new one twice.",
                onClick = onDuressPassword,
            )
            SettingsRow(
                title = "Remove Duress Password",
                description = "Enter the App Password. The second password stops opening anything.",
                onClick = onRemoveDuressPassword,
            )
        }

        SettingsSection("Bulk generation") {
            BulkCapItem(bulkCap = bulkCap, onBulkCap = onBulkCap)
        }

        SettingsSection("Lockout") {
            SettingsRow(
                title = "Wrong password lockout",
                description = failureDelayCap?.let {
                    "${it.summary}. The longest wait after a wrong App Password."
                } ?: "Reading the saved lockout…",
                onClick = onFailureDelayCap,
                loading = failureDelayCap == null,
            )
        }

        SettingsSection("Catalog") {
            SettingsRow(
                title = "Networks",
                description = catalog?.let { it.networks.size.countOf("network", "networks") } ?: "Reading the catalog…",
                onClick = onNetworks,
                loading = catalog == null,
                preview = { IconStack(items = catalog?.networks.orEmpty()) { ChainIcon(network = it, size = PREVIEW_ICON_SIZE) } },
            )
            SettingsRow(
                title = "Tokens",
                description = catalog?.let { it.tokens.size.countOf("token", "tokens") } ?: "Reading the catalog…",
                onClick = onTokens,
                loading = catalog == null,
                preview = { IconStack(items = catalog?.tokens.orEmpty()) { TokenIcon(token = it, size = PREVIEW_ICON_SIZE) } },
            )
            SettingsRow(
                title = "Denylist",
                description = catalog?.let { it.denylist.size.countOf("entry", "entries") } ?: "Reading the catalog…",
                loading = catalog == null,
            )
            SettingsRow(
                title = "Wallet compatibility",
                description = catalog?.let { it.wallets.size.countOf("wallet", "wallets") } ?: "Reading the catalog…",
                loading = catalog == null,
            )
            SettingsRow(
                title = "Update catalog",
                description = "Install a signed package from removable storage. It adds networks and tokens, and sets the " +
                    "approved contracts.",
                onClick = onUpdateCatalog,
            )
            if (catalog?.installedUpdateUnverified == true) {
                SettingsRow(
                    title = "Installed update",
                    description = "Installed update could not be verified; using the built-in catalog",
                )
            }
        }

        SettingsSection("Whitelist") {
            SettingsRow(
                title = "Approved contracts",
                description = catalog?.let { it.whitelist.size.countOf("approved call", "approved calls") }
                    ?: "Reading the catalog…",
                onClick = onApprovedContracts,
                loading = catalog == null,
            )
            SettingsRow(
                title = "Proxy policy",
                description = proxyPolicy?.let { policy ->
                    if (policy == ProxyPolicy.DEFAULT) "${policy.title} (default)" else policy.title
                } ?: "Reading the saved policy…",
                onClick = onProxyPolicy,
                loading = proxyPolicy == null,
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
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
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
 * arrow and presses like a button. A [loading] row shows a progress mark in place of the arrow and the [preview]. A [preview]
 * sits between the text and the arrow.
 */
@Composable
internal fun SettingsRow(
    title: String,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    loading: Boolean = false,
    preview: (@Composable () -> Unit)? = null,
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
            } else {
                if (preview != null) {
                    Spacer(Modifier.width(LenslySpacing.sm))
                    preview()
                }
                if (onClick != null) {
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
}

/**
 * The first few [items] as icons that overlap, the first at the left. Each icon sits on a black disc, so the edge of the one in
 * front shows against the one behind it.
 */
@Composable
private fun <T> IconStack(items: List<T>, icon: @Composable (T) -> Unit) {
    val shown = items.take(PREVIEW_ICONS)
    val step = PREVIEW_ICON_SIZE * 0.6f
    Box(modifier = Modifier.width(PREVIEW_ICON_SIZE + step * (shown.size - 1).coerceAtLeast(0).toFloat())) {
        shown.forEachIndexed { index, item ->
            Box(
                modifier = Modifier
                    .offset(x = step * index.toFloat())
                    .size(PREVIEW_ICON_SIZE)
                    .clip(CircleShape)
                    .background(LenslyBlack),
                contentAlignment = Alignment.Center,
            ) {
                icon(item)
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
