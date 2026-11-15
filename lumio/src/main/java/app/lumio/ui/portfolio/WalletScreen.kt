package app.lumio.ui.portfolio

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.PillChip
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lumio.evm.AddressRow
import app.lumio.evm.ChainFailure
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmFormat
import app.lumio.evm.Total
import app.lumio.evm.Totals
import java.math.BigInteger

/**
 * The portfolio of one paired wallet. Mainnet and testnet totals sit in two lists, so a testnet balance is never shown as part of a mainnet
 * one. Below them, each shown address lists its balance on every enabled chain, with the time of its read. [failures] names the chains that
 * did not refresh; their cached reads stay on the screen.
 */
@Composable
fun WalletScreen(
    walletName: String,
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
    LenslyScreen(
        title = "PORTFOLIO",
        modifier = modifier,
        onBack = onBack,
        bottomAction = {
            PillButton(
                text = if (refreshing) "Reading" else "Refresh",
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                enabled = hasEvm && !refreshing,
            )
        },
    ) {
        Text(walletName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(LenslySpacing.sm))
        if (!hasEvm) {
            EmptyState(
                icon = LenslyIcons.Wallets,
                title = "No EVM account",
                body = "This wallet has no Ethereum account, so it has no EVM balances to show.",
            )
            return@LenslyScreen
        }
        if (chains.isEmpty()) {
            EmptyState(
                icon = LenslyIcons.Wallets,
                title = "No chains are on",
                body = "Turn a chain on in EVM settings to read its balances.",
            )
            return@LenslyScreen
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
        SectionLabel("Mainnet")
        TotalsList(totals.mainnet, emptyText = "No mainnet balance read yet.")
        SectionLabel("Testnet")
        TotalsList(totals.testnet, emptyText = "No testnet balance read yet.")
        totals.oldestMs?.let {
            Text(
                "Oldest read ${EvmFormat.asOf(it)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionLabel("Addresses")
        rows.forEach { row ->
            AddressCard(row = row, chains = chains, onSend = { onSend(row.index) })
            Spacer(Modifier.height(LenslySpacing.sm))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(LenslySpacing.md))
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(LenslySpacing.xs))
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

@Composable
private fun AddressCard(row: AddressRow, chains: List<EvmChain>, onSend: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(LenslyRadius.md))
            .padding(LenslySpacing.md),
    ) {
        Text(
            "Address ${row.index + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(shortAddress(row.address), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(LenslySpacing.xs))
        PillButton(text = "Send from this address", onClick = onSend, modifier = Modifier.fillMaxWidth(), enabled = chains.isNotEmpty())
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
}

/** The address as the screen shows it: the start and the end, which is enough to tell two addresses apart. */
private fun shortAddress(address: String): String = "${address.take(8)}…${address.takeLast(6)}"

private fun chainName(chains: List<EvmChain>, chainId: Long): String =
    chains.firstOrNull { it.chainId == chainId }?.name ?: "Chain $chainId"

private fun networksText(count: Int): String = if (count == 1) "1 network" else "$count networks"
