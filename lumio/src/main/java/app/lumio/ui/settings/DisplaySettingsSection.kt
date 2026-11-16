package app.lumio.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.SettingsItem
import app.lensly.ui.components.SettingsSwitchRow
import app.lensly.ui.theme.LenslySpacing
import app.lumio.data.config.DisplaySettings
import app.lumio.data.config.MinimumUsd

/**
 * The Display settings: what Home shows. Each switch reports its new value at once. The minimum applies as the owner types each valid amount, and an
 * entry that is not an amount stays in the field with its reason. [notice] shows a change that the vault refused.
 */
@Composable
fun DisplaySettingsSection(
    settings: DisplaySettings,
    notice: String?,
    onShowTotal: (Boolean) -> Unit,
    onShowAssets: (Boolean) -> Unit,
    onMinimumUsd: (Double) -> Unit,
) {
    notice?.let {
        InlineBanner(message = it, tone = BannerTone.Error)
        Spacer(Modifier.height(LenslySpacing.sm))
    }
    SettingsSwitchRow(
        title = "Show total balance",
        description = "Shows the combined balance of your assets.",
        checked = settings.showTotal,
        onCheckedChange = onShowTotal,
    )
    SettingsSwitchRow(
        title = "Show individual asset balances",
        description = "Shows each asset's balance.",
        checked = settings.showAssets,
        onCheckedChange = onShowAssets,
    )
    MinimumField(minimumUsd = settings.minimumUsd, onMinimumUsd = onMinimumUsd)
}

/**
 * The minimum balance as a dollar field. The field starts on the saved amount. Each valid amount that differs from the last one asked for is passed
 * on at once, and an entry that is not an amount shows its reason under the field and passes nothing on.
 */
@Composable
private fun MinimumField(minimumUsd: Double, onMinimumUsd: (Double) -> Unit) {
    var text by remember { mutableStateOf(MinimumUsd.format(minimumUsd)) }
    var requested by remember { mutableDoubleStateOf(minimumUsd) }
    val refusal = (minimumEdit(text, requested) as? MinimumEdit.Refused)?.reason

    SettingsItem {
        Column(modifier = Modifier.padding(vertical = LenslySpacing.md)) {
            OutlinedTextField(
                value = text,
                onValueChange = { typed ->
                    text = typed
                    val edit = minimumEdit(typed, requested)
                    if (edit is MinimumEdit.Apply) {
                        requested = edit.amount
                        onMinimumUsd(edit.amount)
                    }
                },
                label = { Text("Minimum balance (USD)") },
                supportingText = {
                    Text(refusal ?: "Assets worth less than this are hidden. Assets with no price, such as testnet coins, are always shown.")
                },
                isError = refusal != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
