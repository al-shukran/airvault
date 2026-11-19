package app.lensly.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.core.btc.BtcFeeThresholds
import app.lensly.ui.components.IslandDivider
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.NumberFieldSetting
import app.lensly.ui.components.SettingsIsland
import app.lensly.ui.components.SliderSetting

/** The fee share limits the slider offers, from the lowest to the highest, one per cent. */
private val SHARE_OPTIONS: List<Int> = (BtcFeeThresholds.MIN_SHARE_PERCENT..BtcFeeThresholds.MAX_SHARE_PERCENT).toList()

/**
 * The fee warnings of a Bitcoin review, as two rows: the fee rate above which it warns, typed into a number field, and the fee share
 * above which it warns, picked on a slider. Each applies as it changes, so there is no Save. [rate] and [share] are the saved limits,
 * null while they are read, and the rows are off until then.
 */
@Composable
fun FeeWarningScreen(
    rate: Int?,
    share: Int?,
    onRate: (Int) -> Unit,
    onShare: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(title = "Fee Warnings", modifier = modifier, onBack = onBack) {
        Text(
            text = "A Bitcoin review warns when its fee rate is above the first limit, or when its fee is above the share of the amount " +
                "sent. A warning does not stop signing.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsIsland("Limits") {
            NumberFieldSetting(
                title = "Fee Rate Above",
                value = rate,
                range = BtcFeeThresholds.MIN_RATE_SAT_PER_VB..BtcFeeThresholds.MAX_RATE_SAT_PER_VB,
                suffix = "sat/vB",
                onValid = onRate,
            )
            IslandDivider()
            SliderSetting(
                title = "Fee Share Above",
                options = SHARE_OPTIONS,
                selected = share,
                label = { "$it%" },
                onSelect = onShare,
            )
        }
    }
}
