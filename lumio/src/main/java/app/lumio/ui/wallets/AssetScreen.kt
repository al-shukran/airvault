package app.lumio.ui.wallets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.price.PriceSeries
import app.lumio.transactions.TxFeedItem
import app.lumio.ui.asset.TickChart
import app.lumio.ui.home.AssetIcon
import app.lumio.ui.kit.ClearAirAmount
import app.lumio.ui.kit.ClearAirCapsuleSmall
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.kit.LocalFiat
import app.lumio.ui.kit.LumioAssetIcon
import app.lumio.ui.transactions.ActivityRowItem
import java.time.ZoneId

/** The logo of the asset at the head of its page. */
private val ICON_SIZE = 48.dp

/**
 * One asset of an account, on Clear Air: its logo and name, its amount with its value in dollars, its price chart when it is priced, Send and Receive,
 * and the activity of the asset. Send is the capsule and Receive the text action, and an asset of nothing offers Receive alone. A [notice] that the
 * account cannot hold the asset yet replaces the actions. The page around it is the route's.
 */
@Composable
internal fun AssetScreen(
    asset: AssetRowModel,
    showBalance: Boolean,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    modifier: Modifier = Modifier,
    chart: PriceSeries? = null,
    activity: List<TxFeedItem> = emptyList(),
    names: Map<String, String> = emptyMap(),
    notice: String? = null,
    onOpenTransaction: (TxFeedItem) -> Unit = {},
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val palette = LocalClearAirPalette.current
    val fiat = LocalFiat.current
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = ClearAirSpacing.xxl)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LumioAssetIcon(testnet = asset.testnet) { AssetIcon(spec = asset.icon, size = ICON_SIZE) }
            Spacer(Modifier.width(ClearAirSpacing.sm))
            Column {
                ClearAirEyebrow(text = asset.name)
                Text(text = asset.network, style = ClearAirType.body, color = palette.ink2)
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs)) {
            ClearAirAmount(text = "${asset.amountText} ${asset.symbol}", hidden = !showBalance, style = ClearAirType.total)
            asset.usd?.let { usd ->
                ClearAirAmount(text = fiat.amount(usd), hidden = !showBalance, style = ClearAirType.rowValue, color = palette.ink2)
            }
        }
        chart?.let { series -> TickChart(series = series, modifier = Modifier.padding(top = ClearAirSpacing.md)) }
        if (notice == null) {
            AssetActions(asset = asset, onSend = onSend, onReceive = onReceive)
        } else {
            WalletNotice(message = notice, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClearAirEyebrow(text = "Activity")
        }
        if (activity.isEmpty()) {
            Text(
                text = "No transactions yet.",
                style = ClearAirType.body,
                color = palette.ink2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.sm),
            )
        }
        activity.forEach { item ->
            ActivityRowItem(item = item, names = names, zone = zone, shown = showBalance, onClick = { onOpenTransaction(item) })
        }
    }
}

/** Send and Receive under the amount. An asset of nothing offers Receive alone. */
@Composable
private fun AssetActions(asset: AssetRowModel, onSend: () -> Unit, onReceive: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (asset.zero) {
            ClearAirCapsuleSmall(text = "Receive", onClick = onReceive)
        } else {
            ClearAirCapsuleSmall(text = "Send ${asset.symbol}", onClick = onSend)
            Spacer(Modifier.width(ClearAirSpacing.md))
            ClearAirTextAction(text = "Receive", onClick = onReceive)
        }
    }
}
