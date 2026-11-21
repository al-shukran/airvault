package app.lensly.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.DotProgress
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LumioTextAction
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.profile.ProfileAvatar
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletEyebrow

/** What an App screen's options sheet is open on: the App's options, or one account with its address as it was when it opened. */
private sealed interface AppSheet {
    data class Account(val index: Int, val address: String) : AppSheet
    data object Options : AppSheet
}

/**
 * The Ethereum App of one wallet: its EVM accounts, one row each, with the account's Display Name or Auto-Name, its label, and its full
 * address. [addresses] is null only until the first addresses are derived. After that the list keeps the rows it has, and an account that
 * was just added shows a waiting row until its address arrives. [accountProfiles] holds the profiles that have anything set, by index.
 *
 * A tap or a long press on a row opens the account's options: [onExportAccounts] with the one index, and [onEditAccount]. The App's options
 * open from the overflow icon and hold [onBulkGenerate], selection, [onPair] and [onScan]. "Add Account" is the text action under the list, and
 * a Single Key wallet shows a note in its place. Selecting puts a checkbox on each row, and the text actions under the list act on the
 * selected accounts, in list order.
 *
 * When any account is in a group, the accounts are listed under the group's name, in name order, with the ungrouped accounts last.
 * Otherwise they form one list with no headers. [listState] is hoisted, so adding an account leaves the scroll position where it was.
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
    refusal: String? = null,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val haptics = rememberKeyHaptics()
    val haptic = LocalHapticFeedback.current
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
    fun endSelection() {
        selecting = false
        selected = emptySet()
    }

    AppAccountsScaffold(
        title = "Ethereum Accounts",
        app = WalletApp.ETHEREUM,
        walletName = wallet.name,
        count = wallet.accountCount,
        onBack = onBack,
        onOptions = { sheet = AppSheet.Options },
        listState = listState,
        modifier = modifier,
        trailing = {
            if (selecting) {
                WalletEyebrow("${selected.size} selected")
                Spacer(Modifier.width(LenslySpacing.xs))
                LumioTextAction(text = "Done", onClick = { endSelection() }, modifier = Modifier.tapHaptic(haptics))
            }
        },
        footer = {
            if (selecting) {
                val any = selected.isNotEmpty()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xxs),
                ) {
                    LumioTextAction(
                        text = "Select All",
                        onClick = { selected = (0 until wallet.accountCount).toSet() },
                        modifier = Modifier.tapHaptic(haptics),
                    )
                    LumioTextAction(
                        text = "Export",
                        onClick = { onExportAccounts(selected.sorted()) },
                        modifier = Modifier.tapHaptic(haptics, enabled = any),
                        enabled = any,
                    )
                    LumioTextAction(
                        text = "Rename",
                        onClick = { onRenameAccounts(selected.sorted()) },
                        modifier = Modifier.tapHaptic(haptics, enabled = any),
                        enabled = any,
                    )
                    LumioTextAction(
                        text = "Style",
                        onClick = { onStyleAccounts(selected.sorted()) },
                        modifier = Modifier.tapHaptic(haptics, enabled = any),
                        enabled = any,
                    )
                    LumioTextAction(
                        text = "Reset Names",
                        onClick = {
                            onResetAccountNames(selected.sorted())
                            endSelection()
                        },
                        modifier = Modifier.tapHaptic(haptics, enabled = any),
                        enabled = any,
                    )
                }
            } else {
                refusal?.let { InlineBanner(message = it, tone = BannerTone.Error) }
                if (singleKey) {
                    // A Single Key has one account, so there is nothing to add and nothing to generate.
                    InlineBanner(message = SINGLE_KEY_ONE_ACCOUNT, tone = BannerTone.Info)
                } else {
                    LumioTextAction(text = "Add Account", onClick = onAddAccount, modifier = Modifier.tapHaptic(haptics))
                }
                Text(
                    text = "Tip: press and hold an account for options",
                    modifier = Modifier.padding(top = LenslySpacing.xs, bottom = LenslySpacing.xs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        accounts = {
            // Each row learns whether a divider goes above it: every row does except the first of the island.
            var placed = 0
            if (addresses == null) {
                item(key = "deriving") {
                    AccountRow(divider = false) { DerivingLine("Deriving Addresses") }
                }
            } else {
                sections.forEach { section ->
                    if (grouped) {
                        val divider = placed++ > 0
                        item(key = "group-${section.group.orEmpty()}") {
                            GroupHeader(name = section.group ?: "Ungrouped", count = section.indices.size, divider = divider)
                        }
                    }
                    section.indices.forEach { index ->
                        val divider = placed++ > 0
                        val openAccount = { sheet = AppSheet.Account(index, addresses[index]) }
                        val toggle = { selected = if (index in selected) selected - index else selected + index }
                        val longPress: () -> Unit = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            openAccount()
                        }
                        item(key = addresses[index]) {
                            AccountRow(
                                divider = divider,
                                modifier = Modifier.animateItem(),
                                onClick = if (selecting) toggle else openAccount,
                                onLongClick = if (selecting) null else longPress,
                                leading = {
                                    if (selecting) {
                                        // The row takes the tap, so the checkbox itself has no handler.
                                        Checkbox(checked = index in selected, onCheckedChange = null)
                                        Spacer(Modifier.width(LenslySpacing.xs))
                                    }
                                    ProfileAvatar(identity = accountIdentity(index), size = 32.dp)
                                },
                            ) {
                                // The Display Name, or the Auto-Name while the account has none. A long name is cut short.
                                Text(
                                    text = accountName(index),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                accountProfiles[index]?.label?.let { label ->
                                    Spacer(Modifier.height(LenslySpacing.xxs))
                                    PillChip(text = label)
                                }
                                Spacer(Modifier.height(LenslySpacing.xxs))
                                // Every address shows the Ethereum logo, and the whole address is shown; it wraps at the spaces between groups.
                                AddressText(
                                    address = addresses[index],
                                    shown = groupedAddress(addresses[index]),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    icon = true,
                                    onClick = if (selecting) toggle else openAccount,
                                )
                            }
                        }
                    }
                }
                pending.forEach { index ->
                    val divider = placed++ > 0
                    item(key = "pending-$index") {
                        AccountRow(divider = divider, modifier = Modifier.animateItem()) { DerivingLine("Deriving Address") }
                    }
                }
            }
        },
    )

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
internal fun GroupHeader(name: String, count: Int, divider: Boolean) {
    AccountRow(divider = divider) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(LenslySpacing.xs))
            WalletEyebrow("$count")
        }
    }
}

/** A row that waits for its accounts: a dot progress, and its words in the eyebrow style. */
@Composable
private fun DerivingLine(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DotProgress(description = label)
        Spacer(Modifier.width(LenslySpacing.sm))
        WalletEyebrow(label)
    }
}
