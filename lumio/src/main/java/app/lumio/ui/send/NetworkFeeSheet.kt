package app.lumio.ui.send

import app.lumio.DebugDemoData

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
import app.lumio.evm.EvmAdvanced
import app.lumio.evm.EvmAdvancedCheck
import app.lumio.evm.EvmAdvancedFields
import app.lumio.evm.EvmField
import app.lumio.evm.EvmFormat
import app.lumio.evm.PreparedSend
import app.lumio.ui.kit.ClearAirField
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.ui.kit.ClearAirStatusMark
import app.lumio.ui.kit.ClearAirStopSlider
import app.lumio.ui.kit.ClearAirTone
import java.math.BigDecimal
import java.math.BigInteger

/** The three stops of the fee slider. Each one sets the priority fee of the suggested send, and the fee cap moves with it. */
internal enum class FeeStop(val label: String) { Slow("Slow"), Normal("Normal"), Fast("Fast") }

private const val GWEI_DECIMALS = 9

/**
 * The fee sheet of an EVM send (F3). A discrete slider picks the stop, and Advanced opens two gwei fields for the fee cap and the priority fee. The
 * stops scale the suggested priority fee by one half, one and two; the fee cap moves by the same amount, so it never falls below the priority fee.
 * Nothing is saved: when the sheet closes, the send with the owner's valid fees is passed to [onApply], and an edit that is refused is dropped.
 */
@Composable
fun SendFeeSheet(
    visible: Boolean,
    suggested: PreparedSend,
    current: PreparedSend,
    timing: String,
    onApply: (PreparedSend) -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    var fields by remember(visible, current) { mutableStateOf(EvmAdvanced.fieldsOf(current)) }
    var advanced by remember(visible, current) { mutableStateOf(DebugDemoData.flag("fee:advanced")) }
    val check = EvmAdvanced.check(suggested, fields)
    val valid = check as? EvmAdvancedCheck.Valid
    val errors = (check as? EvmAdvancedCheck.Invalid)?.errors.orEmpty()
    val stop = stopOf(suggested, fields)
    ClearAirSheet(
        visible = visible,
        onClose = {
            valid?.send?.let(onApply)
            onClose()
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(ClearAirSpacing.lg)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Network Fee", style = ClearAirType.titleL, color = palette.ink, modifier = Modifier.weight(1f))
                Text(
                    text = valid?.let { "Up to ${EvmFormat.amount(it.send.maxFeeWei, it.send.chain.decimals, it.send.chain.symbol)}" } ?: "Check the fees",
                    style = ClearAirType.rowValue,
                    color = palette.ink,
                )
            }
            Text(text = timing, style = ClearAirType.body, color = palette.ink2, modifier = Modifier.padding(top = ClearAirSpacing.xxs))
            Spacer(Modifier.height(ClearAirSpacing.md))
            ClearAirStopSlider(
                labels = FeeStop.entries.map { it.label },
                selected = stop.ordinal,
                onSelect = { index -> fields = fieldsFor(suggested, FeeStop.entries[index]) },
            )
            Spacer(Modifier.height(ClearAirSpacing.md))
            ClearAirRow(
                title = "Advanced",
                description = "Max fee and priority fee, in gwei",
                onClick = { advanced = !advanced },
            )
            if (advanced) {
                ClearAirField(
                    label = "Max Fee per Gas",
                    value = fields.maxFeeGwei,
                    onValueChange = { fields = fields.copy(maxFeeGwei = it) },
                    error = errors[EvmField.MAX_FEE],
                    keyboard = KeyboardType.Decimal,
                    modifier = Modifier.padding(top = ClearAirSpacing.sm),
                )
                ClearAirField(
                    label = "Priority Fee",
                    value = fields.maxPriorityGwei,
                    onValueChange = { fields = fields.copy(maxPriorityGwei = it) },
                    error = errors[EvmField.MAX_PRIORITY],
                    keyboard = KeyboardType.Decimal,
                    modifier = Modifier.padding(top = ClearAirSpacing.sm),
                )
            }
            valid?.warnings?.forEach { warning ->
                Spacer(Modifier.height(ClearAirSpacing.xs))
                ClearAirStatusMark(glyph = "!", text = warning, tone = ClearAirTone.Pending)
            }
            Spacer(Modifier.height(ClearAirSpacing.md))
            Text(text = "Changes apply when you close this sheet.", style = ClearAirType.body, color = palette.ink2)
        }
    }
}

/** The stop of the slider that [fields] sit nearest to, by the priority fee against the suggested one. Off the stops, it is the nearest one. */
internal fun stopOf(suggested: PreparedSend, fields: EvmAdvancedFields): FeeStop {
    val priority = runCatching { weiOf(fields.maxPriorityGwei) }.getOrNull() ?: return FeeStop.Normal
    val base = suggested.maxPriorityFeePerGas
    return when {
        base.signum() == 0 -> FeeStop.Normal
        priority * FOUR < base * THREE -> FeeStop.Slow
        priority * TWO > base * THREE -> FeeStop.Fast
        else -> FeeStop.Normal
    }
}

/** The fields of [stop] for [suggested]: its priority fee, and its fee cap moved by the same amount. */
internal fun fieldsFor(suggested: PreparedSend, stop: FeeStop): EvmAdvancedFields {
    val base = suggested.maxPriorityFeePerGas
    val priority = when (stop) {
        FeeStop.Slow -> base / TWO
        FeeStop.Normal -> base
        FeeStop.Fast -> base * TWO
    }
    val cap = suggested.maxFeePerGas + (priority - base)
    return EvmAdvanced.fieldsOf(suggested).copy(maxFeeGwei = gweiText(cap), maxPriorityGwei = gweiText(priority))
}

private fun weiOf(gwei: String): BigInteger = BigDecimal(gwei).movePointRight(GWEI_DECIMALS).toBigIntegerExact()

private fun gweiText(wei: BigInteger): String = BigDecimal(wei).movePointLeft(GWEI_DECIMALS).stripTrailingZeros().toPlainString()

private val TWO = BigInteger.valueOf(2)
private val THREE = BigInteger.valueOf(3)
private val FOUR = BigInteger.valueOf(4)
