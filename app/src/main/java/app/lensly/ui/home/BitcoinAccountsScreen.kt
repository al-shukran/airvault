package app.lensly.ui.home

import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.lensly.ui.components.DetailTopBar
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.PillChip
import app.lensly.ui.profile.ProfileAvatar
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillSize
import app.lensly.ui.wallet.WalletPillStyle
import app.lensly.ui.wallet.WalletScaffold

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
 * [accounts], which only grows, so it stays the same account. GENERATE ACCOUNTS is the last row for a wallet with a seed, and it opens the
 * bulk screen on this App through [onGenerate]. A Single Key wallet has no seed, so it has no Bitcoin accounts and no generate row, and a
 * note says why. The App's options hold [onPair] and [onScan], and generating again.
 */
@Composable
fun BitcoinAccountsScreen(
    wallet: WalletMeta,
    accounts: List<BitcoinAccountRecord>,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onGenerate: () -> Unit,
    onPair: () -> Unit,
    onScan: () -> Unit,
    onEditAccount: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
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

    WalletScaffold(modifier = modifier, scrollable = false) {
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            item(key = "top-bar") {
                DetailTopBar(
                    onBack = onBack,
                    onLock = onLock,
                    onOptions = { sheetOpen = true },
                    optionsLabel = "App options",
                )
            }
            item(key = "app") { AppHeader(app = WalletApp.BITCOIN, walletName = wallet.name) }
            item(key = "accounts-header") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(LenslySpacing.lg))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WalletEyebrow("Accounts")
                        Spacer(Modifier.weight(1f))
                        WalletEyebrow("${accounts.size}")
                    }
                    Spacer(Modifier.height(LenslySpacing.sm))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterPill(text = "All", chosen = filter == null, onClick = { filter = null })
                        ScriptType.entries.forEach { scriptType ->
                            FilterPill(
                                text = scriptFilterLabel(scriptType),
                                chosen = filter == scriptType,
                                onClick = { filter = scriptType },
                            )
                        }
                    }
                }
            }
            if (singleKey) {
                item(key = "single-key") {
                    Spacer(Modifier.height(LenslySpacing.md))
                    InlineBanner(message = SINGLE_KEY_NO_BITCOIN, tone = BannerTone.Info)
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (accounts.isEmpty()) "No Bitcoin accounts yet." else "No accounts of this script type.",
                        modifier = Modifier.padding(vertical = LenslySpacing.md),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            sections.forEach { section ->
                if (grouped) {
                    item(key = "group-${section.group.orEmpty()}") {
                        GroupHeader(name = section.group ?: "Ungrouped", count = section.indices.size)
                    }
                }
                items(
                    section.indices,
                    key = { position -> "${accounts[position].scriptType}-${accounts[position].network}-${accounts[position].index}" },
                ) { position ->
                    BitcoinAccountRow(
                        account = accounts[position],
                        name = accountName(position),
                        identity = accountIdentity(position),
                        onOptions = { accountSheet = position },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (!singleKey) {
                item(key = "generate") {
                    Spacer(Modifier.height(LenslySpacing.md))
                    AddAccountRow(label = "GENERATE ACCOUNTS", onClick = onGenerate)
                }
                item(key = "tip") {
                    Text(
                        text = "Tip: press and hold an account for options. Pair this wallet to export its Bitcoin accounts",
                        modifier = Modifier.padding(top = LenslySpacing.md, bottom = LenslySpacing.xs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

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

/** One choice of the script filter: the accent when it is the chosen one, an outline otherwise. */
@Composable
private fun FilterPill(text: String, chosen: Boolean, onClick: () -> Unit) {
    WalletPill(
        text = text,
        onClick = onClick,
        style = if (chosen) WalletPillStyle.Accent else WalletPillStyle.Outline,
        size = WalletPillSize.Compact,
    )
}

/**
 * One Bitcoin account: its Visual Identity, its name or Auto-Name in the primary text, its network badge, its label as a chip, its Script
 * Type, and its first receive address in the mono face, grouped in fours without a prefix. A tap or a long press calls [onOptions].
 */
@Composable
private fun BitcoinAccountRow(
    account: BitcoinAccountRecord,
    name: String,
    identity: VisualIdentity,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    role = Role.Button,
                    onClick = onOptions,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOptions()
                    },
                )
                .padding(vertical = LenslySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(identity = identity, size = 32.dp)
            Spacer(Modifier.width(LenslySpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name.uppercase(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = LenslyOnSurface,
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
                    onClick = onOptions,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}
