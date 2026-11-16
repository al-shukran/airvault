package app.lumio.ui.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lensly.ui.components.AddressIcon
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.AppTitle
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.DetailTopBar
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.SettingsRow
import app.lensly.ui.components.PillChip
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillStyle
import app.lensly.ui.wallet.WalletScaffold
import app.lumio.evm.AddressRow
import app.lumio.evm.ChainFailure
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmFormat
import app.lumio.evm.Total
import app.lumio.evm.Totals
import app.lumio.network.NetworkMode
import app.lumio.ui.network.label
import java.math.BigInteger

/**
 * The portfolio of one paired wallet's Ethereum App on the side [mode] chooses. Its total is the side's own, and the other side is not shown,
 * so a testnet balance is never shown as part of a mainnet one. Below it, each shown address lists its balance on every enabled chain of that
 * side, with the time of its read, and a row that opens the send from that address. [failures] names the chains that did not refresh; their
 * cached reads stay on the screen. Refresh is the one primary action; while a read runs, a status line says so.
 */
@Composable
fun WalletScreen(
    walletName: String,
    mode: NetworkMode,
    hasEvm: Boolean,
    chains: List<EvmChain>,
    rows: List<AddressRow>,
    totals: Totals,
    failures: List<ChainFailure>,
    problem: String?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onSend: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    WalletScaffold(
        modifier = modifier,
        footer = {
            WalletPill(
                text = "Refresh",
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Primary,
                enabled = hasEvm && !refreshing,
            )
            Spacer(Modifier.height(LenslySpacing.xs))
        },
    ) {
        DetailTopBar(title = "Ethereum", onBack = onBack)
        AppTitle(logo = { size -> AddressIcon(size = size) }, name = "Ethereum", walletName = walletName)
        Spacer(Modifier.height(LenslySpacing.sm))
        if (refreshing) Caption("Reading the network")
        if (!hasEvm) {
            EmptyState(
                icon = LenslyIcons.Wallets,
                title = "No EVM account",
                body = "This wallet has no Ethereum account, so it has no EVM balances to show.",
            )
            return@WalletScaffold
        }
        if (chains.isEmpty()) {
            EmptyState(
                icon = LenslyIcons.Wallets,
                title = "No ${mode.label().lowercase()} chains are on",
                body = "Turn a chain on in Settings to read its balances.",
            )
            return@WalletScaffold
        }
        problem?.let {
            InlineBanner(message = it, tone = BannerTone.Error)
            Spacer(Modifier.height(LenslySpacing.sm))
        }
        if (failures.isNotEmpty()) {
            InlineBanner(
                title = "Not refreshed",
                message = failures.joinToString("\n") { "${chainName(chains, it.chainId)}: ${it.message}" },
                tone = BannerTone.Warning,
            )
            Spacer(Modifier.height(LenslySpacing.sm))
        }
        SectionLabel(mode.label())
        TotalsList(
            totals = if (mode == NetworkMode.TESTNET) totals.testnet else totals.mainnet,
            emptyText = "No ${mode.label().lowercase()} balance read yet.",
        )
        totals.oldestMs?.let {
            Caption("Oldest read ${EvmFormat.asOf(it)}")
        }
        SectionLabel("Addresses")
        rows.forEach { row ->
            AddressSection(row = row, chains = chains, onSend = { onSend(row.index) })
        }
    }
}

/** The label of a block on the screen, such as the side or the addresses. */
@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(LenslySpacing.md))
    WalletEyebrow(text)
    Spacer(Modifier.height(LenslySpacing.xs))
}

/** A line of muted text: a status, a time, or a caption. */
@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TotalsList(totals: List<Total>, emptyText: String) {
    if (totals.isEmpty()) {
        Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    totals.forEach { total ->
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.xxs)) {
            Text(
                EvmFormat.amount(total.wei, total.decimals, total.symbol),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "on ${networksText(total.networks)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One address: its balance on each chain of the side, then the row that sends from it. */
@Composable
private fun AddressSection(row: AddressRow, chains: List<EvmChain>, onSend: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.sm)) {
        Text(
            "Address ${row.index + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AddressText(
            address = row.address,
            shown = shortAddress(row.address),
            icon = false,
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        chains.forEach { chain ->
            val read = row.reads[chain.chainId]
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.xxs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(chain.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (chain.testnet) PillChip(text = "Testnet")
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        read?.let { EvmFormat.amount(BigInteger(it.wei), chain.decimals, chain.symbol) } ?: "Not read yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    read?.let {
                        Text(
                            EvmFormat.asOf(it.atMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    SettingsRow(
        title = "Send from this address",
        onClick = onSend,
        enabled = chains.isNotEmpty(),
    )
}

/** The address as the screen shows it: the start and the end, which is enough to tell two addresses apart. */
private fun shortAddress(address: String): String = "${address.take(8)}…${address.takeLast(6)}"

private fun chainName(chains: List<EvmChain>, chainId: Long): String =
    chains.firstOrNull { it.chainId == chainId }?.name ?: "Chain $chainId"

private fun networksText(count: Int): String = if (count == 1) "1 network" else "$count networks"
