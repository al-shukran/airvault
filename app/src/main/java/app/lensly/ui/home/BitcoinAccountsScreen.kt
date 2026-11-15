package app.lensly.ui.home

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.app.BitcoinAccountRecord
import app.lensly.app.SINGLE_KEY_NO_BITCOIN
import app.lensly.app.WalletApp
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.DetailTopBar
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.BitcoinIcon
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.PillChip
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
 * The Bitcoin App of one wallet: its Bitcoin accounts, each with its network badge, its Script Type and its first receive address. A
 * filter by Script Type comes first, with "All" chosen at the start, and it keeps the accounts in the order they were made.
 *
 * GENERATE ACCOUNTS is the last row for a wallet with a seed, and it opens the bulk screen on this App through [onGenerate]. A Single
 * Key wallet has no seed, so it has no Bitcoin accounts and no generate row, and a note says why. The App's options hold [onPair]
 * and [onScan], and generating again.
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
    modifier: Modifier = Modifier,
) {
    var filter by rememberSaveable { mutableStateOf<ScriptType?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    val singleKey = wallet.kind == WalletKind.SINGLE_KEY
    val shown = accounts.filter { account -> filter == null || account.scriptType == filter }

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
            items(shown, key = { account -> "${account.scriptType}-${account.network}-${account.index}" }) { account ->
                BitcoinAccountRow(account = account, modifier = Modifier.animateItem())
            }
            if (!singleKey) {
                item(key = "generate") {
                    Spacer(Modifier.height(LenslySpacing.md))
                    AddAccountRow(label = "GENERATE ACCOUNTS", onClick = onGenerate)
                }
                item(key = "tip") {
                    Text(
                        text = "Tip: pair this wallet to export its Bitcoin accounts",
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
 * One Bitcoin account: its number and network badge on the first line, its Script Type, and its first receive address in the mono
 * face, grouped in fours without a prefix.
 */
@Composable
private fun BitcoinAccountRow(account: BitcoinAccountRecord, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BitcoinIcon(size = 32.dp)
            Spacer(Modifier.width(LenslySpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Account ${account.index}".uppercase(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = LenslyOnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    PillChip(text = networkBadge(account.network))
                }
                Spacer(Modifier.height(LenslySpacing.xxs))
                PillChip(text = scriptRowLabel(account.scriptType))
                Spacer(Modifier.height(LenslySpacing.xxs))
                AddressText(
                    address = account.firstReceiveAddress,
                    shown = groupedInFours(account.firstReceiveAddress),
                    icon = false,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}
