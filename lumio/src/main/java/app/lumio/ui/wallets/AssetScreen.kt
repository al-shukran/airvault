package app.lumio.ui.wallets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.LumioIsland
import app.lensly.ui.components.LumioTextAction
import app.lensly.ui.theme.LenslySpacing
import app.lumio.data.price.PriceSeries
import app.lumio.home.Masking
import app.lumio.transactions.TxFeedItem
import app.lumio.ui.asset.AssetChart
import app.lumio.ui.home.AssetIcon
import app.lumio.ui.kit.AnimatedAmount
import app.lumio.ui.kit.LumioAssetIcon
import app.lumio.ui.kit.LumioPrimaryButton
import app.lumio.ui.transactions.ActivityRow
import java.time.ZoneId

/**
 * One asset of an account (section 2.1, Asset): its icon and name, its amount with its value in dollars, its price chart when it is priced, and the
 * activity of the asset. Send is the primary action, and Receive the text action beside it. An asset of nothing makes Receive the primary, and a
 * [notice] that the account cannot hold the asset yet replaces the actions.
 */
@Composable
internal fun AssetScreen(
    asset: AssetRowModel,
    showBalance: Boolean,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    chart: PriceSeries? = null,
    activity: List<TxFeedItem> = emptyList(),
    notice: String? = null,
    onOpenTransaction: (TxFeedItem) -> Unit = {},
    zone: ZoneId = ZoneId.systemDefault(),
) {
    LenslyScreen(
        title = asset.symbol,
        modifier = modifier,
        onBack = onBack,
        bottomAction = {
            when {
                notice != null -> Unit
                asset.zero -> LumioPrimaryButton(text = "Receive", onClick = onReceive)
                else -> {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        LumioTextAction(text = "Receive", onClick = onReceive)
                    }
                    Spacer(Modifier.height(LenslySpacing.xs))
                    LumioPrimaryButton(text = "Send ${asset.symbol}", onClick = onSend)
                }
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LumioAssetIcon(testnet = asset.testnet) { AssetIcon(spec = asset.icon, size = 24.dp) }
            Spacer(Modifier.width(LenslySpacing.sm))
            Column {
                Text(text = asset.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(text = asset.network, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        AnimatedAmount(
            text = "${asset.amountText} ${asset.symbol}",
            hidden = !showBalance,
            style = MaterialTheme.typography.displaySmall,
        )
        asset.fiatText?.let { fiat ->
            Text(
                text = Masking.amount(fiat, hidden = !showBalance),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        chart?.let { series ->
            Spacer(Modifier.height(LenslySpacing.md))
            AssetChart(series = series)
        }
        notice?.let { message ->
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(message = message, tone = BannerTone.Warning)
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        Text(
            text = "Activity",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = LenslySpacing.xs),
        )
        LumioIsland {
            if (activity.isEmpty()) {
                Text(
                    text = "No transactions yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.sm),
                )
            } else {
                activity.forEach { item ->
                    ActivityRow(item = item, zone = zone, shown = showBalance, onClick = { onOpenTransaction(item) })
                }
            }
        }
    }
}
