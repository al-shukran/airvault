package app.lensly.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.profile.ProfileAvatar
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.SpaceMonoFamily
import app.lensly.ui.wallet.DotText
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillSize
import app.lensly.ui.wallet.WalletPillStyle
import app.lensly.ui.wallet.WalletScaffold

/** Why a Single Key wallet offers no add or generate action. The sentence is the spec's wording. */
internal const val SINGLE_KEY_ONE_ACCOUNT =
    "A Wallet built on a Single Key has exactly one Account and cannot generate more."

/**
 * One wallet: its name in the dot-matrix face, its Visual Identity, its label and groups, its accounts, and its actions. EDIT PROFILE
 * under the name opens the wallet's profile. Each account shows its Display Name, or its Auto-Name, with its Visual Identity, its label
 * and a shortened address. Tapping the row shows the full address in groups of four. [addresses] is null while they are derived.
 * [accountProfiles] holds the profiles that have anything set, by index, and [walletPosition] is the wallet's place in the list, which
 * its generated Visual Identity comes from.
 *
 * When any account is in a group, the accounts are listed under the group's name, in name order, with the ungrouped accounts last.
 * Otherwise they form one list with no headers. EDIT PROFILE, on each account, calls [onEditAccount] with its index. EXPORT calls
 * [onExportAccount].
 *
 * SELECT on a Mnemonic wallet turns each account row into a checkbox. The selected accounts can then be renamed in one flow, through
 * [onRenameAccounts], styled in one flow, through [onStyleAccounts], or reset to their Auto-Names, through [onResetAccountNames]. Each
 * gets the selected indices in order. The selection clears once a reset has been sent, and leaving the screen clears it too.
 *
 * The accounts are a LazyColumn, so a wallet with hundreds of accounts composes only the rows on screen. The header scrolls with the
 * list, and the Pair and Sign actions stay pinned under it.
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
    onPair: () -> Unit,
    onSign: () -> Unit,
    onShowSecret: () -> Unit,
    onEditWallet: () -> Unit,
    onEditAccount: (Int) -> Unit,
    onExportAccount: (Int) -> Unit,
    onRenameAccounts: (List<Int>) -> Unit,
    onStyleAccounts: (List<Int>) -> Unit,
    onResetAccountNames: (List<Int>) -> Unit,
    onDeleteWallet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberKeyHaptics()
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    // The accounts in list order, from the addresses that are shown, so a row never refers to an account past the list.
    val sections = remember(addresses, accountProfiles) {
        accountSections(addresses?.indices?.toList().orEmpty()) { index -> accountProfiles[index]?.group }
    }
    val grouped = accountProfiles.values.any { it.group != null }
    val identity: VisualIdentity = visualIdentity(walletProfile.colour, walletProfile.icon, walletPosition)

    WalletScaffold(
        modifier = modifier,
        scrollable = false,
        footer = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = LenslySpacing.md),
                horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
            ) {
                PillButton(
                    text = "Pair wallet",
                    onClick = onPair,
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    text = "Sign",
                    onClick = onSign,
                    modifier = Modifier.weight(1f),
                    accent = true,
                )
            }
        },
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = LenslySpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WalletPill(
                            text = "Back",
                            onClick = onBack,
                            modifier = Modifier.tapHaptic(haptics),
                            style = WalletPillStyle.Outline,
                            size = WalletPillSize.Compact,
                        )
                        Spacer(Modifier.weight(1f))
                        WalletPill(
                            text = "Lock",
                            onClick = onLock,
                            modifier = Modifier.tapHaptic(haptics),
                            style = WalletPillStyle.Outline,
                            size = WalletPillSize.Compact,
                        )
                    }
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (wallet.kind == WalletKind.SINGLE_KEY) {
                            PillChip(text = "Single key")
                        } else {
                            PillChip(text = "Master ${wallet.masterFingerprintHex.orEmpty().uppercase()}")
                        }
                        Spacer(Modifier.weight(1f))
                        WalletPill(
                            text = "Edit profile",
                            onClick = onEditWallet,
                            style = WalletPillStyle.Outline,
                            size = WalletPillSize.Compact,
                        )
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
                    Spacer(Modifier.height(LenslySpacing.lg))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WalletEyebrow("Accounts")
                        Spacer(Modifier.weight(1f))
                        if (wallet.kind == WalletKind.MNEMONIC && addresses != null) {
                            WalletPill(
                                text = if (selecting) "Done" else "Select",
                                onClick = {
                                    selecting = !selecting
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
                item {
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
                        val profile = accountProfiles[index]
                        AccountRow(
                            address = addresses[index],
                            name = profile?.name ?: autoAccountName(wallet.autoNameBase, index),
                            label = profile?.label,
                            identity = visualIdentity(profile?.colour, profile?.icon, index),
                            onEdit = { onEditAccount(index) },
                            onExport = { onExportAccount(index) },
                            selecting = selecting,
                            selected = index in selected,
                            onToggle = { selected = if (index in selected) selected - index else selected + index },
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(LenslySpacing.lg))
                if (wallet.kind == WalletKind.SINGLE_KEY) {
                    // A Single Key has one account, so there is nothing to add and nothing to generate.
                    InlineBanner(message = SINGLE_KEY_ONE_ACCOUNT, tone = BannerTone.Info)
                } else {
                    // Full width, so each label stays on one line.
                    PillButton(
                        text = "+ Account",
                        onClick = onAddAccount,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(LenslySpacing.sm))
                    PillButton(
                        text = "Bulk generate",
                        onClick = onBulkGenerate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Both kinds of wallet can show their secret, so this sits after the account actions.
                Spacer(Modifier.height(LenslySpacing.sm))
                PillButton(
                    text = "Show secret",
                    onClick = onShowSecret,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(LenslySpacing.sm))
                PillButton(
                    text = "Delete wallet",
                    onClick = onDeleteWallet,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(LenslySpacing.xs))
            }
        }
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
 * One account row: its Visual Identity, then its name, or its Auto-Name, in the primary text, with its label as a chip under it. Its
 * short address in the mono face sits last. The row expands to show the full address. EXPORT and EDIT PROFILE sit at the end. While
 * [selecting], the row shows a checkbox instead, [selected] gives its state, and a tap calls [onToggle] rather than expanding the row.
 * The rows are in a list, so they play no haptic.
 */
@Composable
private fun AccountRow(
    address: String,
    name: String,
    label: String?,
    identity: VisualIdentity,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    selecting: Boolean = false,
    selected: Boolean = false,
    onToggle: () -> Unit = {},
) {
    var expanded by rememberSaveable(address) { mutableStateOf(false) }
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = if (selecting) Role.Checkbox else Role.Button) {
                    if (selecting) onToggle() else expanded = !expanded
                }
                .padding(vertical = LenslySpacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Every address shows the Ethereum logo, whichever compatible chain it is used on.
                        AddressIcon(size = 16.dp)
                        Spacer(Modifier.width(LenslySpacing.xs))
                        Text(
                            text = shortAddress(address),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceMonoFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!selecting) {
                    Spacer(Modifier.width(LenslySpacing.sm))
                    WalletPill(
                        text = "Export",
                        onClick = onExport,
                        style = WalletPillStyle.Outline,
                        size = WalletPillSize.Compact,
                    )
                    Spacer(Modifier.width(LenslySpacing.xs))
                    WalletPill(
                        text = "Edit profile",
                        onClick = onEdit,
                        style = WalletPillStyle.Outline,
                        size = WalletPillSize.Compact,
                    )
                }
            }
            if (expanded && !selecting) {
                Spacer(Modifier.height(LenslySpacing.sm))
                Text(
                    text = groupedAddress(address),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceMonoFamily),
                    color = LenslyOnSurface,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/** The first and last four hex digits, for example "0xF278…1cdb". */
internal fun shortAddress(address: String): String = "${address.take(6)}…${address.takeLast(4)}"

/** The full address in groups of four hex digits, five groups to a line. */
internal fun groupedAddress(address: String): String {
    val groups = address.removePrefix("0x").chunked(4)
    return groups.chunked(5)
        .mapIndexed { line, groupsOnLine ->
            (if (line == 0) "0x" else "") + groupsOnLine.joinToString(" ")
        }
        .joinToString("\n")
}
