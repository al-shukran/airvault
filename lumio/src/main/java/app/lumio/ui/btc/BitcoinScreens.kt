package app.lumio.ui.btc

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.PillChip
import app.lensly.ui.qr.AnimatedQr
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.BtcPortfolio
import app.lumio.btc.BtcRow
import app.lumio.btc.BtcSection
import app.lumio.btc.BtcSnapshot
import app.lumio.btc.BtcTx
import app.lumio.btc.asOfText
import app.lumio.btc.btcText
import app.lumio.btc.confirmationsText
import app.lumio.btc.shortTxid
import app.lumio.btc.signedBtcText
import app.lumio.ui.pair.PairLabels

/**
 * Bitcoin portfolio: one section per network, mainnet first. Each section totals its own accounts only, so mainnet and testnet4 are
 * never added together. [failures] names a network whose server could not be reached, with the reason; its rows show the last scan.
 */
@Composable
fun BitcoinPortfolioScreen(
    sections: List<BtcSection>,
    scanning: Boolean,
    failures: Map<BtcNetwork, String>,
    onOpen: (BtcAccountRef) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    LenslyScreen(
        title = "BITCOIN",
        onBack = onBack,
        bottomAction = {
            PillButton(
                text = if (scanning) "Scanning" else "Refresh",
                onClick = onRefresh,
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        if (sections.isEmpty()) {
            EmptyState(
                icon = LenslyIcons.Wallets,
                title = "No Bitcoin accounts paired",
                body = "Pair a wallet from Lensly with a Bitcoin account to see its balances here.",
            )
        }
        failures.forEach { (network, message) ->
            InlineBanner(message = "${networkTitle(network)}: $message", tone = BannerTone.Warning)
            Spacer(Modifier.height(LenslySpacing.sm))
        }
        sections.forEach { section ->
            NetworkHeader(section.network)
            Spacer(Modifier.height(LenslySpacing.xs))
            SectionTotals(section)
            Spacer(Modifier.height(LenslySpacing.sm))
            section.rows.forEach { row ->
                AccountRow(row = row, onClick = { onOpen(row.ref) })
                Spacer(Modifier.height(LenslySpacing.sm))
            }
            Spacer(Modifier.height(LenslySpacing.lg))
        }
    }
}

/**
 * One Bitcoin account: its balance, the address to receive on with its QR code, its unspent outputs and its recent transactions. Each
 * transaction opens on mempool.space. [failure] says why the last refresh did not reach the server; the balance shown is then the cache.
 */
@Composable
fun BitcoinAccountScreen(
    row: BtcRow,
    scanning: Boolean,
    failure: String?,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    val account = row.ref.account
    val snapshot = row.snapshot
    val uriHandler = LocalUriHandler.current
    val receive = snapshot?.nextReceiveAddress ?: account.receiveAddress(0)
    LenslyScreen(
        title = "BITCOIN",
        onBack = onBack,
        bottomAction = {
            PillButton(
                text = if (scanning) "Scanning" else "Refresh",
                onClick = onRefresh,
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        AccountHeader(account)
        Spacer(Modifier.height(LenslySpacing.md))
        failure?.let {
            InlineBanner(message = it, tone = BannerTone.Warning)
            Spacer(Modifier.height(LenslySpacing.md))
        }
        if (snapshot == null) {
            Caption("Not scanned yet. Refresh when the server can be reached.")
        } else {
            Label("Balance")
            Text(btcText(snapshot.confirmedSats), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            if (snapshot.unconfirmedSats != 0L) {
                Caption("Pending ${signedBtcText(snapshot.unconfirmedSats)}")
            }
            Caption(asOfText(snapshot.scannedAtMs))
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        Label(if (snapshot == null) "Receive" else "Next unused receive address")
        Text(receive, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(LenslySpacing.sm))
        AnimatedQr(parts = listOf(receive), modifier = Modifier.fillMaxWidth(), keepCase = true)
        if (snapshot == null) {
            Spacer(Modifier.height(LenslySpacing.xs))
            Caption("Refresh to find the next unused address.")
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        if (snapshot != null) {
            Label("Unspent outputs")
            Text("${snapshot.utxos.size}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(LenslySpacing.lg))
            Label("Recent transactions")
            if (snapshot.transactions.isEmpty()) {
                Caption("No transactions yet.")
            }
            snapshot.transactions.forEach { tx ->
                TransactionRow(
                    snapshot = snapshot,
                    tx = tx,
                    network = account.network,
                    onOpen = { uriHandler.openUri(BtcPortfolio.explorerTxUrl(account.network, tx.txid)) },
                )
                Spacer(Modifier.height(LenslySpacing.sm))
            }
        }
    }
}

@Composable
private fun NetworkHeader(network: BtcNetwork) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
        Text(networkTitle(network), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        if (network == BtcNetwork.TESTNET4) PillChip(text = PairLabels.network(network))
    }
}

@Composable
private fun SectionTotals(section: BtcSection) {
    Column {
        Label("Confirmed")
        Text(btcText(section.confirmedSats), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (section.unconfirmedSats != 0L) {
            Caption("Pending ${signedBtcText(section.unconfirmedSats)}")
        }
    }
}

@Composable
private fun AccountRow(row: BtcRow, onClick: () -> Unit) {
    val account = row.ref.account
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(LenslyRadius.md))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(LenslySpacing.md),
    ) {
        Text(PairLabels.scriptType(account.scriptType), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Caption(account.path.asString('\''))
        Spacer(Modifier.height(LenslySpacing.xs))
        val snapshot = row.snapshot
        if (snapshot == null) {
            Caption("Not scanned yet")
        } else {
            Text(btcText(snapshot.confirmedSats), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (snapshot.unconfirmedSats != 0L) Caption("Pending ${signedBtcText(snapshot.unconfirmedSats)}")
            Caption(asOfText(snapshot.scannedAtMs))
        }
    }
}

@Composable
private fun AccountHeader(account: BtcWatchOnlyAccount) {
    Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
        PillChip(text = PairLabels.scriptType(account.scriptType))
        if (account.network == BtcNetwork.TESTNET4) PillChip(text = PairLabels.network(account.network))
    }
    Spacer(Modifier.height(LenslySpacing.xxs))
    Caption(account.path.asString('\''))
}

@Composable
private fun TransactionRow(snapshot: BtcSnapshot, tx: BtcTx, network: BtcNetwork, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(LenslyRadius.md))
            .clickable(role = Role.Button, onClickLabel = "Open on mempool.space", onClick = onOpen)
            .padding(LenslySpacing.md),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(signedBtcText(tx.netSats), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                confirmationsText(snapshot.confirmations(tx)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Caption(shortTxid(tx.txid))
        Caption("Open on mempool.space")
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The name of a network as the portfolio heads it. */
private fun networkTitle(network: BtcNetwork): String = when (network) {
    BtcNetwork.MAINNET -> "Bitcoin mainnet"
    BtcNetwork.TESTNET4 -> "Bitcoin testnet4"
}
