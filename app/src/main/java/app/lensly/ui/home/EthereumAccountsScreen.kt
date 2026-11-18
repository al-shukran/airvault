package app.lensly.ui.home

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.app.AccountProfile
import app.lensly.app.VisualIdentity
import app.lensly.app.WalletApp
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.accountSections
import app.lensly.app.autoAccountName
import app.lensly.app.visualIdentity
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.DetailTopBar
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.DotProgress
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.profile.ProfileAvatar
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillSize
import app.lensly.ui.wallet.WalletPillStyle
import app.lensly.ui.wallet.WalletScaffold

/** What an App screen's options sheet is open on: the App's options, or one account with its address as it was when it opened. */
private sealed interface AppSheet {
    data class Account(val index: Int, val address: String) : AppSheet
    data object Options : AppSheet
}

/**
 * The Ethereum App of one wallet: its EVM accounts, one row each, with the account's Display Name or Auto-Name, its label, and its full
 * address. [addresses] is null only until the first addresses are derived. After that the list keeps the rows it has, and an account
 * that was just added shows a spinner row until its address arrives. [accountProfiles] holds the profiles that have anything set, by
 * index.
 *
 * A tap or a long press on a row opens the account's options: [onExportAccounts] with the one index, and [onEditAccount]. The App's
 * options open from the overflow icon and hold [onBulkGenerate], selection, [onPair] and [onScan]. "Add account" is the last row, and a
 * Single Key wallet shows a note in its place. SELECT turns each row into a checkbox. The selected accounts can then be exported,
 * renamed, styled, or reset to their Auto-Names, through [onExportAccounts], [onRenameAccounts], [onStyleAccounts] and
 * [onResetAccountNames], each of which gets the selected indices in order.
 *
 * When any account is in a group, the accounts are listed under the group's name, in name order, with the ungrouped accounts last.
 * Otherwise they form one list with no headers. The accounts are a LazyColumn, so a wallet with hundreds of accounts composes only the
 * rows on screen. [listState] is hoisted, so adding an account leaves the scroll position where it was.
 */
@Composable
fun EthereumAccountsScreen(
    wallet: WalletMeta,
    addresses: List<String>?,
    accountProfiles: Map<Int, AccountProfile>,
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    onBulkGenerate: () -> Unit,
    onPair: () -> Unit,
    onScan: () -> Unit,
    onEditAccount: (Int) -> Unit,
    onExportAccounts: (List<Int>) -> Unit,
    onRenameAccounts: (List<Int>) -> Unit,
    onStyleAccounts: (List<Int>) -> Unit,
    onResetAccountNames: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val haptics = rememberKeyHaptics()
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    var sheet by remember { mutableStateOf<AppSheet?>(null) }
    // The accounts in list order, from the addresses that are shown, so a row never refers to an account past the list.
    val sections = remember(addresses, accountProfiles) {
        accountSections(addresses?.indices?.toList().orEmpty()) { index -> accountProfiles[index]?.group }
    }
    val grouped = accountProfiles.values.any { it.group != null }
    val singleKey = wallet.kind == WalletKind.SINGLE_KEY
    // The accounts the wallet has but the list does not show yet. Their addresses come in one at a time, so they go last.
    val pending = addresses?.let { shown -> (shown.size until wallet.accountCount).toList() }.orEmpty()
    fun accountName(index: Int): String = accountProfiles[index]?.name ?: autoAccountName(wallet.autoNameBase, index)
    fun accountIdentity(index: Int): VisualIdentity =
        visualIdentity(accountProfiles[index]?.colour, accountProfiles[index]?.icon, index)

    WalletScaffold(
        modifier = modifier,
        scrollable = false,
        topBar = {
            DetailTopBar(
                title = "Ethereum accounts",
                onBack = onBack,
                onOptions = { sheet = AppSheet.Options },
                optionsLabel = "App options",
            )
        },
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            item(key = "app") { AppHeader(app = WalletApp.ETHEREUM, walletName = wallet.name) }
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
                                text = "Export",
                                onClick = { onExportAccounts(selected.sorted()) },
                                modifier = Modifier.weight(1f),
                                style = WalletPillStyle.Outline,
                                size = WalletPillSize.Compact,
                                enabled = selected.isNotEmpty(),
                            )
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
                            onOptions = { sheet = AppSheet.Account(index, addresses[index]) },
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
                if (singleKey) {
                    // A Single Key has one account, so there is nothing to add and nothing to generate.
                    InlineBanner(message = SINGLE_KEY_ONE_ACCOUNT, tone = BannerTone.Info)
                } else {
                    AddAccountRow(label = "ADD ACCOUNT", onClick = onAddAccount)
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
        is AppSheet.Account -> AccountOptionsSheet(
            name = accountName(open.index),
            identity = accountIdentity(open.index),
            address = open.address,
            onDismiss = { sheet = null },
            onExport = {
                sheet = null
                onExportAccounts(listOf(open.index))
            },
            onEdit = {
                sheet = null
                onEditAccount(open.index)
            },
        )
        AppSheet.Options -> AppOptionsSheet(
            app = WalletApp.ETHEREUM,
            onDismiss = { sheet = null },
            onGenerate = if (singleKey) null else ({
                sheet = null
                onBulkGenerate()
            }),
            onSelect = if (singleKey || addresses == null) null else ({
                sheet = null
                selecting = true
                selected = emptySet()
            }),
            onPair = {
                sheet = null
                onPair()
            },
            onScan = {
                sheet = null
                onScan()
            },
        )
        null -> Unit
    }
}

/** A group's name over its accounts, with the count of them. The ungrouped accounts are headed "Ungrouped". */
@Composable
internal fun GroupHeader(name: String, count: Int) {
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
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (label != null) {
                    Spacer(Modifier.height(LenslySpacing.xxs))
                    PillChip(text = label)
                }
                Spacer(Modifier.height(LenslySpacing.xxs))
                // Every address shows the Ethereum logo, whichever compatible chain it is used on. The whole address is shown,
                // never cut short; it wraps at the spaces between the groups. A tap opens the row's options, as the row does.
                AddressText(
                    address = address,
                    shown = groupedAddress(address),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = if (selecting) onToggle else onOptions,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

