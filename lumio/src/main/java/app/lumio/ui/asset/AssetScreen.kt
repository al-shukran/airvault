package app.lumio.ui.asset

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.lensly.core.registry.Amounts
import app.lensly.core.registry.NetworkEntry
import app.lensly.ui.components.AppTitle
import app.lensly.ui.components.BitcoinIcon
import app.lensly.ui.components.ChainIcon
import app.lensly.ui.components.DetailTopBar
import app.lensly.ui.components.LenslySegmentedControl
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.SettingsRow
import app.lensly.ui.theme.LenslyError
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySuccess
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillStyle
import app.lensly.ui.wallet.WalletScaffold
import app.lumio.data.net.Family
import app.lumio.data.price.ChartRange
import app.lumio.data.price.PriceSeries
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.evm.EvmFormat
import app.lumio.home.AccountOption
import app.lumio.home.HomeAsset
import app.lumio.home.HomeFormat
import app.lumio.home.Masking
import app.lumio.home.NativeAsset
import app.lumio.home.TokenAsset
import app.lumio.ui.home.AccountPickSheet

/** The test tag of the balance. Its text is the balance, or the mask while it is hidden. */
const val ASSET_BALANCE_TAG = "asset-balance"

/** The test tag of the value in dollars. Its text is the value, or the mask while it is hidden. */
const val ASSET_USD_TAG = "asset-usd"

/** The test tag of the change over 24 hours. */
const val ASSET_CHANGE_TAG = "asset-change"

/** The test tag of the drawn price chart. */
const val ASSET_CHART_TAG = "asset-chart"

/** The test tag of the line that stands in for the chart when there is no price to draw. */
const val ASSET_CHART_EMPTY_TAG = "asset-chart-empty"

/** The test tags of the Send and Receive actions. */
const val ASSET_SEND_TAG = "asset-send"
const val ASSET_RECEIVE_TAG = "asset-receive"

/** The test tag of the line that stands in for the transactions when there are none. */
const val ASSET_TX_EMPTY_TAG = "asset-tx-empty"

private const val CHART_LOADING = "Loading the price chart."
private const val CHART_EMPTY = "No price data for this range."
private const val NO_VALUE = "—"

/** The two actions of the page, which open a picker when more than one account can take them. */
private enum class AssetAction { SEND, RECEIVE }

/**
 * The page of one coin: its header and chain, its balance and value with the change over 24 hours, its price chart over the range the owner picks,
 * then Send and Receive, the transactions of the coin, and the accounts that hold it. [hidden] masks the balance, the value and the amounts, as the
 * settings ask. [series] is the chart, or null when no source answered, and [chartLoading] says a read is running. An action with one account takes it
 * at once, and one with several opens a picker. An action with no account is disabled.
 */
@Composable
fun AssetScreen(
    asset: HomeAsset,
    entry: NetworkEntry?,
    hidden: Boolean,
    range: ChartRange,
    onRange: (ChartRange) -> Unit,
    series: PriceSeries?,
    chartLoading: Boolean,
    transactions: List<LumioTx>,
    sendOptions: List<AccountOption>,
    receiveOptions: List<AccountOption>,
    accounts: List<AccountOption>,
    onSend: (AccountOption) -> Unit,
    onReceive: (AccountOption) -> Unit,
    onAccount: (AccountOption) -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf<AssetAction?>(null) }
    fun act(action: AssetAction, options: List<AccountOption>, pick: (AccountOption) -> Unit) {
        when (options.size) {
            0 -> Unit
            1 -> pick(options.single())
            else -> picking = action
        }
    }

    WalletScaffold(modifier = modifier) {
        DetailTopBar(onBack = onBack, onLock = onLock)
        AppTitle(
            logo = { size ->
                if (asset.network.family == Family.BTC) {
                    BitcoinIcon(size = size)
                } else {
                    ChainIcon(network = entry, size = size)
                }
            },
            name = asset.symbol,
            walletName = if (asset.testnet) "${asset.name} · Testnet" else asset.name,
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        PillChip(text = badgeOf(asset))
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = Masking.amount("${HomeFormat.coins(asset.balance)} ${asset.symbol}", hidden),
            style = MaterialTheme.typography.headlineMedium,
            color = LenslyOnSurface,
            modifier = Modifier.testTag(ASSET_BALANCE_TAG),
        )
        Text(
            text = Masking.amount(asset.usd?.let { HomeFormat.usd(it) } ?: NO_VALUE, hidden),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ASSET_USD_TAG),
        )
        asset.change24h?.let { change ->
            Text(
                text = HomeFormat.change(change),
                style = MaterialTheme.typography.bodyMedium,
                color = if (change >= 0.0) LenslySuccess else LenslyError,
                modifier = Modifier.testTag(ASSET_CHANGE_TAG),
            )
        }
        Spacer(Modifier.height(LenslySpacing.md))
        LenslySegmentedControl(
            options = ChartRange.entries,
            selected = range,
            label = { it.label },
            onSelect = onRange,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        val drawn = series?.takeIf { it.points.isNotEmpty() }
        if (drawn != null) {
            AssetChart(series = drawn, modifier = Modifier.testTag(ASSET_CHART_TAG))
        } else {
            Text(
                text = if (chartLoading) CHART_LOADING else CHART_EMPTY,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(ASSET_CHART_EMPTY_TAG),
            )
        }
        Spacer(Modifier.height(LenslySpacing.md))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm)) {
            WalletPill(
                text = "Send",
                onClick = { act(AssetAction.SEND, sendOptions, onSend) },
                modifier = Modifier.weight(1f).testTag(ASSET_SEND_TAG),
                style = WalletPillStyle.Primary,
                enabled = sendOptions.isNotEmpty(),
            )
            WalletPill(
                text = "Receive",
                onClick = { act(AssetAction.RECEIVE, receiveOptions, onReceive) },
                modifier = Modifier.weight(1f).testTag(ASSET_RECEIVE_TAG),
                style = WalletPillStyle.Outline,
                enabled = receiveOptions.isNotEmpty(),
            )
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        WalletEyebrow("Transactions")
        Spacer(Modifier.height(LenslySpacing.xs))
        if (transactions.isEmpty()) {
            Text(
                text = "No transactions for this coin yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(ASSET_TX_EMPTY_TAG),
            )
        } else {
            transactions.forEach { tx -> AssetTxRow(tx = tx, hidden = hidden) }
        }
        if (accounts.isNotEmpty()) {
            Spacer(Modifier.height(LenslySpacing.md))
            WalletEyebrow("Accounts")
            Spacer(Modifier.height(LenslySpacing.xs))
            accounts.forEach { option ->
                SettingsRow(title = option.title, description = option.detail, onClick = { onAccount(option) })
            }
        }
    }

    when (picking) {
        AssetAction.SEND -> AccountPickSheet(
            title = "Send from",
            options = sendOptions,
            emptyText = "No account can send this coin.",
            onPick = {
                picking = null
                onSend(it)
            },
            onDismiss = { picking = null },
        )
        AssetAction.RECEIVE -> AccountPickSheet(
            title = "Receive to",
            options = receiveOptions,
            emptyText = "No account can receive this coin.",
            onPick = {
                picking = null
                onReceive(it)
            },
            onDismiss = { picking = null },
        )
        null -> Unit
    }
}

/** One transaction of the coin: its signed amount, then its direction, its time and its status. [hidden] masks the amount. */
@Composable
private fun AssetTxRow(tx: LumioTx, hidden: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.xs)) {
        Text(
            text = Masking.amount(amountOf(tx), hidden),
            style = MaterialTheme.typography.bodyLarge,
            color = LenslyOnSurface,
        )
        Text(
            text = "${directionOf(tx.direction)} · ${EvmFormat.asOf(tx.timestampMs)} · ${statusOf(tx.status)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The text of the chain badge: the native coin, or the token's contract shortened. */
private fun badgeOf(asset: HomeAsset): String = when (asset) {
    is NativeAsset -> "Native coin"
    is TokenAsset -> "ERC-20 ${asset.contract.take(6)}...${asset.contract.takeLast(4)}"
}

/** The signed amount of [tx] in whole coins with its symbol, such as "+0.5 ETH". */
private fun amountOf(tx: LumioTx): String {
    val sign = when (tx.direction) {
        TxDirection.IN -> "+"
        TxDirection.OUT -> "-"
        TxDirection.SELF -> ""
    }
    return "$sign${Amounts.format(tx.amount, tx.asset.decimals)} ${tx.asset.symbol}"
}

private fun directionOf(direction: TxDirection): String = when (direction) {
    TxDirection.IN -> "Received"
    TxDirection.OUT -> "Sent"
    TxDirection.SELF -> "Moved to itself"
}

private fun statusOf(status: TxStatus): String = when (status) {
    TxStatus.PENDING -> "Pending"
    TxStatus.CONFIRMED -> "Confirmed"
    TxStatus.FAILED -> "Failed"
}
