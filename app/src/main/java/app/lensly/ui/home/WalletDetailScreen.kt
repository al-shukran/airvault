package app.lensly.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.defaultAccountLabel
import app.lensly.ui.components.AddressIcon
import app.lensly.ui.components.PillChip
import app.lensly.ui.theme.LenslyOnSurface
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
 * One wallet: its name in the dot-matrix face, its accounts, and its actions. Each account shows its Display Name, or its
 * default label, and a shortened address. Tapping the row shows the full address in groups of four. [addresses] is null
 * while they are derived. [accountNames] holds the names of the renamed accounts, by index. RENAME, on the wallet and on
 * each account, opens the name screen.
 *
 * The accounts are a LazyColumn, so a wallet with hundreds of accounts composes only the rows on screen. The header
 * scrolls with the list, and the Pair and Sign actions stay pinned under it.
 */
@Composable
fun WalletDetailScreen(
    wallet: WalletMeta,
    addresses: List<String>?,
    accountNames: Map<Int, String>,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onAddAccount: () -> Unit,
    onBulkGenerate: () -> Unit,
    onPair: () -> Unit,
    onSign: () -> Unit,
    onShowSecret: () -> Unit,
    onRenameWallet: () -> Unit,
    onRenameAccount: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    WalletScaffold(
        modifier = modifier,
        scrollable = false,
        footer = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WalletPill(
                    text = "Pair wallet",
                    onClick = onPair,
                    modifier = Modifier.weight(1f),
                    style = WalletPillStyle.Secondary,
                )
                WalletPill(
                    text = "Sign",
                    onClick = onSign,
                    modifier = Modifier.weight(1f),
                    style = WalletPillStyle.Primary,
                )
            }
        },
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WalletPill(
                            text = "Back",
                            onClick = onBack,
                            style = WalletPillStyle.Outline,
                            size = WalletPillSize.Compact,
                        )
                        Spacer(Modifier.weight(1f))
                        WalletPill(
                            text = "Lock",
                            onClick = onLock,
                            style = WalletPillStyle.Outline,
                            size = WalletPillSize.Compact,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    WalletEyebrow(if (wallet.deviceGenerated) "Device-generated wallet" else "Wallet")
                    Spacer(Modifier.height(8.dp))
                    DotText(text = wallet.name.uppercase(), fontSize = 40.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (wallet.kind == WalletKind.SINGLE_KEY) {
                            PillChip(text = "Single key")
                        } else {
                            PillChip(text = "Master ${wallet.masterFingerprintHex.orEmpty().uppercase()}")
                        }
                        Spacer(Modifier.weight(1f))
                        WalletPill(
                            text = "Rename",
                            onClick = onRenameWallet,
                            style = WalletPillStyle.Outline,
                            size = WalletPillSize.Compact,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    WalletEyebrow("Accounts")
                    Spacer(Modifier.height(4.dp))
                }
            }
            if (addresses == null) {
                item {
                    Text(
                        text = "Deriving addresses…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                itemsIndexed(addresses, key = { _, address -> address }) { index, address ->
                    AccountRow(
                        index = index,
                        address = address,
                        name = accountNames[index],
                        onRename = { onRenameAccount(index) },
                    )
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                if (wallet.kind == WalletKind.SINGLE_KEY) {
                    // A Single Key has one account, so there is nothing to add and nothing to generate.
                    Text(
                        text = SINGLE_KEY_ONE_ACCOUNT,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        WalletPill(
                            text = "+ Account",
                            onClick = onAddAccount,
                            modifier = Modifier.weight(1f),
                            style = WalletPillStyle.Secondary,
                        )
                        WalletPill(
                            text = "Bulk generate",
                            onClick = onBulkGenerate,
                            modifier = Modifier.weight(1f),
                            style = WalletPillStyle.Secondary,
                        )
                    }
                }
                // Both kinds of wallet can show their secret, so this sits after the account actions.
                Spacer(Modifier.height(12.dp))
                WalletPill(
                    text = "Show secret",
                    onClick = onShowSecret,
                    modifier = Modifier.fillMaxWidth(),
                    style = WalletPillStyle.Secondary,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun AccountRow(index: Int, address: String, name: String?, onRename: () -> Unit) {
    var expanded by rememberSaveable(address) { mutableStateOf(false) }
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The Display Name, or the default label while the account has none. A long name is cut short.
                WalletEyebrow(
                    text = name ?: defaultAccountLabel(index),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(12.dp))
                // Every address shows the Ethereum logo, whichever compatible chain it is used on.
                AddressIcon(size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = shortAddress(address),
                    style = MaterialTheme.typography.bodyLarge,
                    color = LenslyOnSurface,
                )
                Spacer(Modifier.width(12.dp))
                WalletPill(
                    text = "Rename",
                    onClick = onRename,
                    style = WalletPillStyle.Outline,
                    size = WalletPillSize.Compact,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = groupedAddress(address),
                    style = MaterialTheme.typography.bodyMedium,
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
