package app.lumio.ui.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.btc.BtcAdvanced
import app.lumio.btc.BtcSend
import app.lumio.btc.BtcSendInput
import app.lumio.ui.kit.ClearAirField
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.ui.kit.ClearAirStatusMark
import app.lumio.ui.kit.ClearAirStopSlider
import app.lumio.ui.kit.ClearAirTone
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The fee sheet of a Bitcoin send (F3): the fee rate as a whole number of sat/vB, a three-stop slider that sets it, and the fee warnings as one note.
 * The stops are the server's suggested rate times one half, one and two, the same rule as the EVM fee. Nothing is saved: when the sheet closes, a
 * text the field holds validly is passed to [onApply], and the send is planned again at that rate.
 */
@Composable
fun SendFeeRateSheet(
    visible: Boolean,
    suggestedRate: Double,
    rate: Double,
    networkFee: String?,
    timing: String,
    onApply: (String) -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    var text by remember(visible, rate) { mutableStateOf(BtcSend.formatFeeRate(rate)) }
    val parsed = runCatching { BtcSendInput.feeRate(text) }
    val value = parsed.getOrNull()
    val error = parsed.exceptionOrNull()?.message?.takeIf { text.isNotBlank() }
    val selected = FeeStop.entries.indexOf(feeRateStop(suggestedRate, value ?: rate))
    val warnings = value?.let { BtcAdvanced.warnings(suggestedRate, it) }.orEmpty()
    ClearAirSheet(
        visible = visible,
        onClose = {
            if (value != null) onApply(text)
            onClose()
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(ClearAirSpacing.lg)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Network Fee", style = ClearAirType.titleL, color = palette.ink, modifier = Modifier.weight(1f))
                Text(text = networkFee.orEmpty(), style = ClearAirType.rowValue, color = palette.ink)
            }
            Text(text = timing, style = ClearAirType.body, color = palette.ink2, modifier = Modifier.padding(top = ClearAirSpacing.xxs))
            Spacer(Modifier.height(ClearAirSpacing.md))
            ClearAirStopSlider(
                labels = FeeStop.entries.map { it.label },
                selected = selected,
                onSelect = { index -> text = BtcSend.formatFeeRate(stopRate(suggestedRate, FeeStop.entries[index])) },
            )
            Spacer(Modifier.height(ClearAirSpacing.md))
            ClearAirField(
                label = "Fee Rate",
                value = text,
                onValueChange = { text = it },
                error = error,
                keyboard = KeyboardType.Number,
                trailing = { Text(text = "sat/vB", style = ClearAirType.rowValue, color = palette.ink2) },
            )
            Text(
                text = "Any whole number from 1 to 10,000",
                style = ClearAirType.body,
                color = palette.ink2,
                modifier = Modifier.padding(top = ClearAirSpacing.xxs),
            )
            Spacer(Modifier.height(ClearAirSpacing.xs))
            Text(text = "A slow fee can take hours to confirm when Bitcoin is busy.", style = ClearAirType.body, color = palette.ink2)
            if (warnings.isNotEmpty()) {
                Spacer(Modifier.height(ClearAirSpacing.xs))
                ClearAirStatusMark(glyph = "!", text = warnings.joinToString(" "), tone = ClearAirTone.Pending)
            }
            Spacer(Modifier.height(ClearAirSpacing.md))
            Text(text = "Changes apply when you close this sheet.", style = ClearAirType.body, color = palette.ink2)
        }
    }
}

/** The rate of [stop] for the server's [suggested] rate: its half, itself or its double, as a whole number of sat/vB and never below one. */
internal fun stopRate(suggested: Double, stop: FeeStop): Double {
    val factor = when (stop) {
        FeeStop.Slow -> 0.5
        FeeStop.Normal -> 1.0
        FeeStop.Fast -> 2.0
    }
    return (suggested * factor).roundToLong().coerceAtLeast(1L).toDouble()
}

/** The stop the [rate] sits nearest to, by its distance from each stop's rate. */
internal fun feeRateStop(suggested: Double, rate: Double): FeeStop =
    FeeStop.entries.minBy { abs(stopRate(suggested, it) - rate) }
