package app.lensly.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.app.BitcoinAccountRecord
import app.lensly.app.SINGLE_KEY_NO_BITCOIN
import app.lensly.app.VisualIdentity
import app.lensly.app.WalletApp
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.accountSections
import app.lensly.app.autoAccountName
import app.lensly.app.visualIdentity
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LumioTextAction
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.profile.ProfileAvatar
import app.lensly.ui.theme.LenslySpacing

/** The name of a Script Type, as the filter and the rows show it. */
private fun scriptName(scriptType: ScriptType): String = when (scriptType) {
    ScriptType.LEGACY -> "Legacy"
    ScriptType.NESTED_SEGWIT -> "Nested SegWit"
    ScriptType.NATIVE_SEGWIT -> "Native SegWit"
    ScriptType.TAPROOT -> "Taproot"
}

/** The label of a Script Type on the filter: its name and its BIP, the purpose of its account path. */
internal fun scriptFilterLabel(scriptType: ScriptType): String = "${scriptName(scriptType)} BIP${scriptType.purpose}"

/** The Script Type of an account on its row, with its BIP after a dot, so it reads apart from the filter label. */
private fun scriptRowLabel(scriptType: ScriptType): String = "${scriptName(scriptType)} · BIP${scriptType.purpose}"

/** The network badge of a Bitcoin account: Mainnet, or Testnet for testnet4. */
private fun networkBadge(network: BtcNetwork): String = if (network == BtcNetwork.MAINNET) "Mainnet" else "Testnet"

/**
 * The Bitcoin App of one wallet: its Bitcoin accounts, one row each, with the account's Display Name or Auto-Name, its label, its network
 * badge, its Script Type and its first receive address. A filter by Script Type comes first, with "All" chosen at the start, and it keeps
 * the accounts in the order they were made. When any account is in a group, the accounts are listed under the group's name, as the
 * Ethereum accounts are.
 *
 * A tap or a long press on a row opens the account's options, which hold [onEditAccount]. [onEditAccount] gets the account's place in
 * [accounts], which only grows, so it stays the same account. "Generate Accounts" is the text action under the list, and it opens the bulk
 * screen on this App through [onGenerate]. A Single Key wallet has no seed, so it has no Bitcoin accounts and no generate action, and a
 * note says why. The App's options hold [onPair] and [onScan], and generating again.
 */
@Composable
fun BitcoinAccountsScreen(
    wallet: WalletMeta,
    accounts: List<BitcoinAccountRecord>,
    onBack: () -> Unit,
    onGenerate: () -> Unit,
    onPair: () -> Unit,
    onScan: () -> Unit,
    onEditAccount: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberKeyHaptics()
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    var filter by rememberSaveable { mutableStateOf<ScriptType?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    // The place of the account whose options are open, or null while none is.
    var accountSheet by remember { mutableStateOf<Int?>(null) }
    val singleKey = wallet.kind == WalletKind.SINGLE_KEY
    // The places of the accounts the filter keeps, in the order they were made.
    val shown = accounts.indices.filter { position -> filter == null || accounts[position].scriptType == filter }
    val grouped = accounts.any { it.profile.group != null }
    val sections = accountSections(shown) { position -> accounts[position].profile.group }
    fun accountName(position: Int): String =
        accounts[position].profile.name ?: autoAccountName(wallet.autoNameBase, accounts[position].index)
    fun accountIdentity(position: Int): VisualIdentity =
        visualIdentity(accounts[position].profile.colour, accounts[position].profile.icon, position)

    AppAccountsScaffold(
        title = "Bitcoin Accounts",
        app = WalletApp.BITCOIN,
        walletName = wallet.name,
        count = accounts.size,
        onBack = onBack,
        onOptions = { sheetOpen = true },
        listState = listState,
        modifier = modifier,
        beforeAccounts = {
            ScriptFilter(chosen = filter, onChoose = { filter = it })
        },
        footer = {
            if (singleKey) {
                InlineBanner(message = SINGLE_KEY_NO_BITCOIN, tone = BannerTone.Info)
            } else {
                LumioTextAction(text = "Generate Accounts", onClick = onGenerate, modifier = Modifier.tapHaptic(haptics))
                Text(
                    text = "Tip: press and hold an account for options. Pair This Wallet to export its Bitcoin accounts",
                    modifier = Modifier.padding(top = LenslySpacing.xs, bottom = LenslySpacing.xs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        accounts = {
            // Each row learns whether a divider goes above it: every row does except the first of the island.
            var placed = 0
            if (shown.isEmpty()) {
                item(key = "empty") {
                    AccountRow(divider = false) {
                        Text(
                            text = if (accounts.isEmpty()) "No Bitcoin accounts yet." else "No accounts of this script type.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            sections.forEach { section ->
                if (grouped) {
                    val divider = placed++ > 0
                    item(key = "group-${section.group.orEmpty()}") {
                        GroupHeader(name = section.group ?: "Ungrouped", count = section.indices.size, divider = divider)
                    }
                }
                section.indices.forEach { position ->
                    val divider = placed++ > 0
                    val account = accounts[position]
                    val openAccount = { accountSheet = position }
                    val longPress: () -> Unit = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        openAccount()
                    }
                    item(key = "${account.scriptType}-${account.network}-${account.index}") {
                        AccountRow(
                            divider = divider,
                            modifier = Modifier.animateItem(),
                            onClick = openAccount,
                            onLongClick = longPress,
                            leading = { ProfileAvatar(identity = accountIdentity(position), size = 32.dp) },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = accountName(position),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                PillChip(text = networkBadge(account.network))
                            }
                            account.profile.label?.let { label ->
                                Spacer(Modifier.height(LenslySpacing.xxs))
                                PillChip(text = label)
                            }
                            Spacer(Modifier.height(LenslySpacing.xxs))
                            PillChip(text = scriptRowLabel(account.scriptType))
                            Spacer(Modifier.height(LenslySpacing.xxs))
                            AddressText(
                                address = account.firstReceiveAddress,
                                shown = groupedInFours(account.firstReceiveAddress),
                                icon = false,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                onClick = openAccount,
                            )
                        }
                    }
                }
            }
        },
    )

    if (sheetOpen) {
        AppOptionsSheet(
            app = WalletApp.BITCOIN,
            onDismiss = { sheetOpen = false },
            onGenerate = if (singleKey) null else ({
                sheetOpen = false
                onGenerate()
            }),
            onSelect = null,
            onPair = {
                sheetOpen = false
                onPair()
            },
            onScan = {
                sheetOpen = false
                onScan()
            },
        )
    }

    accountSheet?.let { position ->
        AccountOptionsSheet(
            name = accountName(position),
            identity = accountIdentity(position),
            address = null,
            onDismiss = { accountSheet = null },
            onExport = null,
            onEdit = {
                accountSheet = null
                onEditAccount(position)
            },
        )
    }
}

/**
 * The script filter: "All" and one choice per Script Type, in words under the heading. The chosen one is bright and the others dim, and the
 * row scrolls sideways when the labels do not fit.
 */
@Composable
private fun ScriptFilter(chosen: ScriptType?, onChoose: (ScriptType?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterText(text = "All", chosen = chosen == null, onClick = { onChoose(null) })
        ScriptType.entries.forEach { scriptType ->
            FilterText(
                text = scriptFilterLabel(scriptType),
                chosen = chosen == scriptType,
                onClick = { onChoose(scriptType) },
            )
        }
    }
}

/** One choice of the script filter: a line of text with a 48 dp touch target, bright while it is the chosen one. */
@Composable
private fun FilterText(text: String, chosen: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
