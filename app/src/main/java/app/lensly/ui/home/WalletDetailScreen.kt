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
import app.lensly.app.autoAccountName
import app.lensly.ui.components.AddressIcon
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.DotProgress
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
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
    onDeleteWallet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberKeyHaptics()
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
                    DotText(text = wallet.name.uppercase(), fontSize = 40.sp)
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
                            text = "Rename",
                            onClick = onRenameWallet,
                            style = WalletPillStyle.Outline,
                            size = WalletPillSize.Compact,
                        )
                    }
                    Spacer(Modifier.height(LenslySpacing.lg))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WalletEyebrow("Accounts")
                        Spacer(Modifier.weight(1f))
                        WalletEyebrow("${wallet.accountCount}")
                    }
                    Spacer(Modifier.height(LenslySpacing.xxs))
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
                itemsIndexed(addresses, key = { _, address -> address }) { index, address ->
                    AccountRow(
                        address = address,
                        label = accountNames[index] ?: autoAccountName(wallet.autoNameBase, index),
                        onRename = { onRenameAccount(index) },
                    )
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

/**
 * One account row: its Display Name, or its Auto-Name, in the primary text, then its short address in the mono face.
 * The row expands to show the full address. Its Rename pill sits at the end. The rows are in a list, so they play no
 * haptic.
 */
@Composable
private fun AccountRow(address: String, label: String, onRename: () -> Unit) {
    var expanded by rememberSaveable(address) { mutableStateOf(false) }
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(vertical = LenslySpacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // The Display Name, or the Auto-Name while the account has none. A long name is cut short.
                    Text(
                        text = label.uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        color = LenslyOnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                Spacer(Modifier.width(LenslySpacing.sm))
                WalletPill(
                    text = "Rename",
                    onClick = onRename,
                    style = WalletPillStyle.Outline,
                    size = WalletPillSize.Compact,
                )
            }
            if (expanded) {
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
