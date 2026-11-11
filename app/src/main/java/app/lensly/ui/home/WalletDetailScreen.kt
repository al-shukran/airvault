package app.lensly.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.app.AccountProfile
import app.lensly.app.VisualIdentity
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.app.accountSections
import app.lensly.app.autoAccountName
import app.lensly.app.visualIdentity
import app.lensly.ui.components.AddressIcon
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.DotProgress
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.profile.ProfileAvatar
import app.lensly.ui.theme.LenslyError
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutlineInteractive
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.SpaceMonoFamily
import app.lensly.ui.wallet.DotText
import app.lensly.ui.wallet.WALLET_GUTTER
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillSize
import app.lensly.ui.wallet.WalletPillStyle
import app.lensly.ui.wallet.WalletScaffold

/** Why a Single Key wallet offers no add or generate action. The sentence is the spec's wording. */
internal const val SINGLE_KEY_ONE_ACCOUNT =
    "A Wallet built on a Single Key has exactly one Account and cannot generate more."

/** The warning the owner reads before a secret is shown. */
private const val SECRET_WARNING =
    "This reveals your secret recovery phrase / private key. Anyone who sees it can take all funds. " +
        "Make sure no one and no camera can see your screen."

/** What an options sheet is open on: one account, with its address as it was when the sheet opened, or the wallet. */
private sealed interface DetailSheet {
    data class Account(val index: Int, val address: String) : DetailSheet
    data object Wallet : DetailSheet
}

/**
 * One wallet: its name in the dot-matrix face, its Visual Identity, its label and groups, its accounts, and its actions.
 * [addresses] is null only until the first addresses are derived. After that the list keeps the rows it has, and an account
 * that was just added shows a spinner row until its address arrives. [accountProfiles] holds the profiles that have anything
 * set, by index, and [walletPosition] is the wallet's place in the list, which its generated Visual Identity comes from.
 *
 * Each account row shows its Display Name, or its Auto-Name, its label, and its full address in groups of four. A tap or a long
 * press on a row opens the account's options: [onExportAccount] and [onEditAccount], with its index. The wallet's options open from
 * the overflow icon or a long press on the header. They hold [onEditWallet], [onBulkGenerate], selection, [onShowSecret] behind a
 * warning, and [onDeleteWallet].
 *
 * When any account is in a group, the accounts are listed under the group's name, in name order, with the ungrouped accounts last.
 * Otherwise they form one list with no headers. "Add account" is the last row, and a Single Key wallet shows a note in its place.
 *
 * SELECT on a Mnemonic wallet turns each account row into a checkbox. The selected accounts can then be renamed in one flow, through
 * [onRenameAccounts], styled in one flow, through [onStyleAccounts], or reset to their Auto-Names, through [onResetAccountNames]. Each
 * gets the selected indices in order. The selection clears once a reset has been sent, and leaving the screen clears it too.
 *
 * The accounts are a LazyColumn, so a wallet with hundreds of accounts composes only the rows on screen. [listState] is hoisted, so
 * adding an account leaves the scroll position where it was.
 */
@Composable
fun WalletDetailScreen(
    wallet: WalletMeta,
    addresses: List<String>?,
    accountProfiles: Map<Int, AccountProfile>,
    walletProfile: WalletProfile,
    walletPosition: Int,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onAddAccount: () -> Unit,
    onBulkGenerate: () -> Unit,
    onShowSecret: () -> Unit,
    onEditWallet: () -> Unit,
    onEditAccount: (Int) -> Unit,
    onExportAccount: (Int) -> Unit,
    onRenameAccounts: (List<Int>) -> Unit,
    onStyleAccounts: (List<Int>) -> Unit,
    onResetAccountNames: (List<Int>) -> Unit,
    onDeleteWallet: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val haptics = rememberKeyHaptics()
    val haptic = LocalHapticFeedback.current
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    var sheet by remember { mutableStateOf<DetailSheet?>(null) }
    // The accounts in list order, from the addresses that are shown, so a row never refers to an account past the list.
    val sections = remember(addresses, accountProfiles) {
        accountSections(addresses?.indices?.toList().orEmpty()) { index -> accountProfiles[index]?.group }
    }
    val grouped = accountProfiles.values.any { it.group != null }
    val identity: VisualIdentity = visualIdentity(walletProfile.colour, walletProfile.icon, walletPosition)
    // The accounts the wallet has but the list does not show yet. Their addresses come in one at a time, so they go last.
    val pending = addresses?.let { shown -> (shown.size until wallet.accountCount).toList() }.orEmpty()
    fun accountName(index: Int): String = accountProfiles[index]?.name ?: autoAccountName(wallet.autoNameBase, index)
    fun accountIdentity(index: Int): VisualIdentity =
        visualIdentity(accountProfiles[index]?.colour, accountProfiles[index]?.icon, index)

    WalletScaffold(modifier = modifier, scrollable = false) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            item(key = "top-bar") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = LenslySpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.tapHaptic(haptics)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = LenslyOnSurface)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onLock, modifier = Modifier.tapHaptic(haptics)) {
                        Icon(Icons.Filled.Lock, contentDescription = "Lock", tint = LenslyOnSurface)
                    }
                    IconButton(onClick = { sheet = DetailSheet.Wallet }, modifier = Modifier.tapHaptic(haptics)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Wallet options", tint = LenslyOnSurface)
                    }
                }
            }
            item(key = "wallet") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    sheet = DetailSheet.Wallet
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
            item(key = "accounts-header") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(LenslySpacing.lg))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WalletEyebrow("Accounts")
                        Spacer(Modifier.weight(1f))
                        if (selecting) {
                            WalletPill(
                                text = "Done",
                                onClick = {
                                    selecting = false
                                    selected = emptySet()
                                },
                                modifier = Modifier.tapHaptic(haptics),
                                style = WalletPillStyle.Outline,
                                size = WalletPillSize.Compact,
                            )
                            Spacer(Modifier.width(LenslySpacing.xs))
                        }
                        WalletEyebrow("${wallet.accountCount}")
                    }
                    Spacer(Modifier.height(LenslySpacing.xxs))
                    if (selecting) {
                        Spacer(Modifier.height(LenslySpacing.xs))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            WalletEyebrow("${selected.size} selected")
                            Spacer(Modifier.weight(1f))
                            WalletPill(
                                text = "Select all",
                                onClick = { selected = (0 until wallet.accountCount).toSet() },
                                modifier = Modifier.tapHaptic(haptics),
                                style = WalletPillStyle.Outline,
                                size = WalletPillSize.Compact,
                            )
                        }
                        Spacer(Modifier.height(LenslySpacing.sm))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = LenslySpacing.xs),
                            horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
                        ) {
                            WalletPill(
                                text = "Rename",
                                onClick = { onRenameAccounts(selected.sorted()) },
                                modifier = Modifier.weight(1f),
                                style = WalletPillStyle.Outline,
                                size = WalletPillSize.Compact,
                                enabled = selected.isNotEmpty(),
                            )
                            WalletPill(
                                text = "Style",
                                onClick = { onStyleAccounts(selected.sorted()) },
                                modifier = Modifier.weight(1f),
                                style = WalletPillStyle.Outline,
                                size = WalletPillSize.Compact,
                                enabled = selected.isNotEmpty(),
                            )
                            WalletPill(
                                text = "Reset names",
                                onClick = {
                                    onResetAccountNames(selected.sorted())
                                    selecting = false
                                    selected = emptySet()
                                },
                                modifier = Modifier.weight(1f),
                                style = WalletPillStyle.Outline,
                                size = WalletPillSize.Compact,
                                enabled = selected.isNotEmpty(),
                            )
                        }
                    }
                }
            }
            if (addresses == null) {
                item(key = "deriving") {
                    Row(
                        modifier = Modifier.padding(vertical = LenslySpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DotProgress(description = "Deriving addresses")
                        Spacer(Modifier.width(LenslySpacing.sm))
                        WalletEyebrow("Deriving addresses")
                    }
                }
            } else {
                sections.forEach { section ->
                    if (grouped) {
                        item(key = "group-${section.group.orEmpty()}") {
                            GroupHeader(name = section.group ?: "Ungrouped", count = section.indices.size)
                        }
                    }
                    items(section.indices, key = { index -> addresses[index] }) { index ->
                        AccountRow(
                            address = addresses[index],
                            name = accountName(index),
                            label = accountProfiles[index]?.label,
                            identity = accountIdentity(index),
                            selecting = selecting,
                            selected = index in selected,
                            onOptions = { sheet = DetailSheet.Account(index, addresses[index]) },
                            onToggle = { selected = if (index in selected) selected - index else selected + index },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                items(pending, key = { index -> "pending-$index" }) {
                    PendingAccountRow(modifier = Modifier.animateItem())
                }
            }
            item(key = "add") {
                Spacer(Modifier.height(LenslySpacing.md))
                if (wallet.kind == WalletKind.SINGLE_KEY) {
                    // A Single Key has one account, so there is nothing to add and nothing to generate.
                    InlineBanner(message = SINGLE_KEY_ONE_ACCOUNT, tone = BannerTone.Info)
                } else {
                    AddAccountRow(onClick = onAddAccount)
                }
            }
            item(key = "tip") {
                Text(
                    text = "Tip: press and hold an account for options",
                    modifier = Modifier.padding(top = LenslySpacing.md, bottom = LenslySpacing.xs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    when (val open = sheet) {
        is DetailSheet.Account -> AccountOptionsSheet(
            name = accountName(open.index),
            identity = accountIdentity(open.index),
            address = open.address,
            onDismiss = { sheet = null },
            onExport = {
                sheet = null
                onExportAccount(open.index)
            },
            onEdit = {
                sheet = null
                onEditAccount(open.index)
            },
        )
        DetailSheet.Wallet -> WalletOptionsSheet(
            name = wallet.name,
            identity = identity,
            singleKey = wallet.kind == WalletKind.SINGLE_KEY,
            listed = addresses != null,
            onDismiss = { sheet = null },
            onEdit = {
                sheet = null
                onEditWallet()
            },
            onGenerate = {
                sheet = null
                onBulkGenerate()
            },
            onSelect = {
                sheet = null
                selecting = true
                selected = emptySet()
            },
            onShowSecret = {
                sheet = null
                onShowSecret()
            },
            onDelete = {
                sheet = null
                onDeleteWallet()
            },
        )
        null -> Unit
    }
}

/** A group's name over its accounts, with the count of them. The ungrouped accounts are headed "Ungrouped". */
@Composable
private fun GroupHeader(name: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = LenslySpacing.md, bottom = LenslySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WalletEyebrow(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(LenslySpacing.xs))
        WalletEyebrow("$count")
    }
}

/**
 * One account row: its Visual Identity, its name or Auto-Name in the primary text, its label as a chip, and its full address in
 * the mono face, grouped in fours. A tap or a long press calls [onOptions], which opens the account's options. While [selecting],
 * the row shows a checkbox, [selected] gives its state, and a tap calls [onToggle] instead.
 */
@Composable
private fun AccountRow(
    address: String,
    name: String,
    label: String?,
    identity: VisualIdentity,
    selecting: Boolean,
    selected: Boolean,
    onOptions: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val longPress: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onOptions()
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    role = if (selecting) Role.Checkbox else Role.Button,
                    onClick = if (selecting) onToggle else onOptions,
                    onLongClick = if (selecting) null else longPress,
                )
                .padding(vertical = LenslySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                // The row takes the tap, so the checkbox itself has no handler.
                Checkbox(checked = selected, onCheckedChange = null)
                Spacer(Modifier.width(LenslySpacing.sm))
            }
            ProfileAvatar(identity = identity, size = 32.dp)
            Spacer(Modifier.width(LenslySpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                // The Display Name, or the Auto-Name while the account has none. A long name is cut short.
                Text(
                    text = name.uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    color = LenslyOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (label != null) {
                    Spacer(Modifier.height(LenslySpacing.xxs))
                    PillChip(text = label)
                }
                Spacer(Modifier.height(LenslySpacing.xxs))
                Row(verticalAlignment = Alignment.Top) {
                    // Every address shows the Ethereum logo, whichever compatible chain it is used on.
                    AddressIcon(size = 16.dp)
                    Spacer(Modifier.width(LenslySpacing.xs))
                    // The whole address, never cut short. It wraps at the spaces between the groups.
                    Text(
                        text = groupedAddress(address),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceMonoFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/** An account that was just added, while its address is derived. Its row replaces this one when the address is ready. */
@Composable
private fun PendingAccountRow(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(vertical = LenslySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DotProgress(description = "Deriving address")
            Spacer(Modifier.width(LenslySpacing.sm))
            WalletEyebrow("Deriving address")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/** The last row of the list: a dashed outline with a plus, which adds the next account. */
@Composable
private fun AddAccountRow(onClick: () -> Unit) {
    val haptics = rememberKeyHaptics()
    val radius = LenslyRadius.md
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = LenslyOutlineInteractive,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(radius.toPx()),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
                )
            }
            .tapHaptic(haptics)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = LenslyOnSurface,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(LenslySpacing.xs))
        Text(
            text = "ADD ACCOUNT",
            style = MaterialTheme.typography.labelLarge,
            color = LenslyOnSurface,
        )
    }
}

/**
 * A Lensly-styled sheet of options. It closes on a swipe down, a tap outside it, or Back. The content goes in the gutter, with
 * the sheet's own bottom padding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
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
                .padding(bottom = LenslySpacing.lg),
            content = content,
        )
    }
}

/** The look and the name of what the sheet is about. An account also shows its full address, in groups of four. */
@Composable
private fun SheetHeader(name: String, identity: VisualIdentity, address: String?) {
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
private fun SheetOption(
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

/** The options of one account: its address goes out as a code, or its profile is edited. The address is public. */
@Composable
private fun AccountOptionsSheet(
    name: String,
    identity: VisualIdentity,
    address: String,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onEdit: () -> Unit,
) {
    OptionsSheet(onDismiss = onDismiss) {
        SheetHeader(name = name, identity = identity, address = address)
        SheetOption(icon = Icons.Filled.Share, label = "Export address", onClick = onExport)
        SheetOption(icon = Icons.Filled.Edit, label = "Edit profile", onClick = onEdit)
    }
}

/**
 * The options of the wallet. A Single Key wallet has no generate or select actions, and its secret is its private key. The
 * secret is one step behind a warning: the first page only asks for the confirmation, and [onShowSecret] runs after it.
 */
@Composable
private fun WalletOptionsSheet(
    name: String,
    identity: VisualIdentity,
    singleKey: Boolean,
    listed: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onGenerate: () -> Unit,
    onSelect: () -> Unit,
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
            if (!singleKey) {
                SheetOption(icon = Icons.Filled.Add, label = "Generate accounts", onClick = onGenerate)
            }
            if (!singleKey && listed) {
                SheetOption(icon = Icons.Filled.Check, label = "Select accounts", onClick = onSelect)
            }
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

/** The full address in groups of four hex digits, five groups to a line. */
internal fun groupedAddress(address: String): String {
    val groups = address.removePrefix("0x").chunked(4)
    return groups.chunked(5)
        .mapIndexed { line, groupsOnLine ->
            (if (line == 0) "0x" else "") + groupsOnLine.joinToString(" ")
        }
        .joinToString("\n")
}
