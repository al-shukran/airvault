package app.lensly.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.app.VisualIdentity
import app.lensly.app.WalletApp
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.app.visualIdentity
import app.lensly.ui.components.DetailTopBar
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.profile.ProfileAvatar
import app.lensly.ui.theme.LenslyError
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.SpaceMonoFamily
import app.lensly.ui.wallet.DotText
import app.lensly.ui.wallet.WALLET_GUTTER
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillStyle
import app.lensly.ui.wallet.WalletScaffold

/** Why a Single Key wallet offers no add or generate action. The sentence is the spec's wording. */
internal const val SINGLE_KEY_ONE_ACCOUNT =
    "A Wallet built on a Single Key has exactly one Account and cannot generate more."

/** The warning the owner reads before a secret is shown. */
private const val SECRET_WARNING =
    "This reveals your secret recovery phrase / private key. Anyone who sees it can take all funds. " +
        "Make sure no one and no camera can see your screen."

/**
 * One wallet: its name in the dot-matrix face, its Visual Identity, its label and groups, its Apps, and its options. Each App is a
 * row with its logo and its account count, and a tap on the row calls [onOpenApp], which opens that App's accounts. [bitcoinAccounts]
 * is the number of the wallet's Bitcoin accounts, and the Ethereum count is the wallet's account count.
 *
 * The wallet's options open from the overflow icon or a long press on the header. They hold [onEditWallet], [onHistory],
 * [onShowSecret] behind a warning, and [onDeleteWallet]. Generating, selecting, pairing and signing are on each App.
 */
@Composable
fun WalletDetailScreen(
    wallet: WalletMeta,
    walletProfile: WalletProfile,
    walletPosition: Int,
    bitcoinAccounts: Int,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onOpenApp: (WalletApp) -> Unit,
    onShowSecret: () -> Unit,
    onHistory: () -> Unit,
    onEditWallet: () -> Unit,
    onDeleteWallet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var optionsOpen by remember { mutableStateOf(false) }
    val identity: VisualIdentity = visualIdentity(walletProfile.colour, walletProfile.icon, walletPosition)

    WalletScaffold(modifier = modifier, scrollable = false) {
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            item(key = "top-bar") {
                DetailTopBar(
                    onBack = onBack,
                    onLock = onLock,
                    onOptions = { optionsOpen = true },
                    optionsLabel = "Wallet options",
                )
            }
            item(key = "wallet") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    optionsOpen = true
                                },
                            )
                        },
                ) {
                    Spacer(Modifier.height(LenslySpacing.lg))
                    WalletEyebrow(if (wallet.deviceGenerated) "Device-generated wallet" else "Wallet")
                    Spacer(Modifier.height(LenslySpacing.xs))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProfileAvatar(identity = identity, size = 40.dp)
                        Spacer(Modifier.width(LenslySpacing.sm))
                        DotText(text = wallet.name.uppercase(), modifier = Modifier.weight(1f), fontSize = 40.sp)
                    }
                    Spacer(Modifier.height(LenslySpacing.sm))
                    if (wallet.kind == WalletKind.SINGLE_KEY) {
                        PillChip(text = "Single key")
                    } else {
                        PillChip(text = "Master ${wallet.masterFingerprintHex.orEmpty().uppercase()}")
                    }
                    if (walletProfile.label != null || walletProfile.groups.isNotEmpty()) {
                        Spacer(Modifier.height(LenslySpacing.xs))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            walletProfile.label?.let { PillChip(text = it) }
                            walletProfile.groups.forEach { group -> PillChip(text = group) }
                        }
                    }
                }
            }
            item(key = "apps-header") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(LenslySpacing.lg))
                    WalletEyebrow("Apps")
                    Spacer(Modifier.height(LenslySpacing.xxs))
                }
            }
            items(WalletApp.entries, key = { app -> app.routeId }) { app ->
                AppRow(
                    app = app,
                    count = when (app) {
                        WalletApp.BITCOIN -> bitcoinAccounts
                        WalletApp.ETHEREUM -> wallet.accountCount
                    },
                    onClick = { onOpenApp(app) },
                )
            }
            item(key = "tip") {
                Text(
                    text = "Tip: press and hold the wallet name for its options",
                    modifier = Modifier.padding(top = LenslySpacing.md, bottom = LenslySpacing.xs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (optionsOpen) {
        WalletOptionsSheet(
            name = wallet.name,
            identity = identity,
            singleKey = wallet.kind == WalletKind.SINGLE_KEY,
            onDismiss = { optionsOpen = false },
            onEdit = {
                optionsOpen = false
                onEditWallet()
            },
            onHistory = {
                optionsOpen = false
                onHistory()
            },
            onShowSecret = {
                optionsOpen = false
                onShowSecret()
            },
            onDelete = {
                optionsOpen = false
                onDeleteWallet()
            },
        )
    }
}

/** One App of the wallet: its logo, its name, and how many accounts it holds. A tap on the row calls [onClick]. */
@Composable
private fun AppRow(app: WalletApp, count: Int, onClick: () -> Unit) {
    val haptics = rememberKeyHaptics()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .tapHaptic(haptics)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = LenslySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app = app, size = 32.dp)
            Spacer(Modifier.width(LenslySpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label.uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    color = LenslyOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(LenslySpacing.xxs))
                Text(
                    text = accountCountLabel(count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = LenslyOnSurface,
                modifier = Modifier.size(20.dp),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * The options of the wallet. A Single Key wallet's secret is its private key. The secret is one step behind a warning: the first
 * page only asks for the confirmation, and [onShowSecret] runs after it.
 */
@Composable
private fun WalletOptionsSheet(
    name: String,
    identity: VisualIdentity,
    singleKey: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    onShowSecret: () -> Unit,
    onDelete: () -> Unit,
) {
    val secretLabel = if (singleKey) "Show private key" else "Show secret phrase"
    var confirmingSecret by remember { mutableStateOf(false) }
    OptionsSheet(onDismiss = onDismiss) {
        SheetHeader(name = name, identity = identity, address = null)
        if (confirmingSecret) {
            SecretWarning(
                title = secretLabel,
                onCancel = { confirmingSecret = false },
                onReveal = onShowSecret,
            )
        } else {
            SheetOption(icon = Icons.Filled.Edit, label = "Edit wallet profile", onClick = onEdit)
            SheetOption(icon = LenslyIcons.History, label = "History", onClick = onHistory)
            SheetOption(
                icon = Icons.Filled.Warning,
                label = secretLabel,
                onClick = { confirmingSecret = true },
                destructive = true,
            )
            SheetOption(icon = Icons.Filled.Delete, label = "Delete wallet", onClick = onDelete, destructive = true)
        }
    }
}

/** The warning before a secret. Cancel goes back to the options. Reveal opens the secret route, which asks for the App Password. */
@Composable
private fun SecretWarning(title: String, onCancel: () -> Unit, onReveal: () -> Unit) {
    Text(text = title, style = MaterialTheme.typography.titleMedium, color = LenslyOnSurface)
    Spacer(Modifier.height(LenslySpacing.sm))
    InlineBanner(title = "Keep it private", message = SECRET_WARNING, tone = BannerTone.Warning)
    Spacer(Modifier.height(LenslySpacing.md))
    Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
        WalletPill(
            text = "Cancel",
            onClick = onCancel,
            modifier = Modifier.weight(1f),
            style = WalletPillStyle.Outline,
        )
        WalletPill(
            text = "Reveal",
            onClick = onReveal,
            modifier = Modifier.weight(1f),
            style = WalletPillStyle.Accent,
        )
    }
}

/**
 * A Lensly-styled sheet of options. It closes on a swipe down, a tap outside it, or Back. The content goes in the gutter, with
 * the sheet's own bottom padding. With [scrollable] the content scrolls when it is taller than the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OptionsSheet(onDismiss: () -> Unit, scrollable: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = LenslyOnSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WALLET_GUTTER)
                .padding(bottom = LenslySpacing.lg)
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            content = content,
        )
    }
}

/** The look and the name of what the sheet is about. An account also shows its full address, in groups of four. */
@Composable
internal fun SheetHeader(name: String, identity: VisualIdentity, address: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = LenslySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(identity = identity, size = 40.dp)
        Spacer(Modifier.width(LenslySpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name.uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = LenslyOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (address != null) {
                Spacer(Modifier.height(LenslySpacing.xxs))
                Text(
                    text = groupedAddress(address),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(bottom = LenslySpacing.xs),
    )
}

/** One option in a sheet: its icon and its label, in a row that takes a tap. [destructive] puts both in the error colour. */
@Composable
internal fun SheetOption(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val haptics = rememberKeyHaptics()
    val tint = if (destructive) LenslyError else LenslyOnSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .tapHaptic(haptics)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(LenslySpacing.md))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/** The full address in groups of four hex digits, five groups to a line. */
internal fun groupedAddress(address: String): String = groupedInFours(address.removePrefix("0x"), prefix = "0x")

/**
 * [text] in groups of four characters, five groups to a line, with [prefix] before the first line. A Bitcoin address has no prefix,
 * so it passes none.
 */
internal fun groupedInFours(text: String, prefix: String = ""): String {
    val groups = text.chunked(4)
    return groups.chunked(5)
        .mapIndexed { line, groupsOnLine ->
            (if (line == 0) prefix else "") + groupsOnLine.joinToString(" ")
        }
        .joinToString("\n")
}
