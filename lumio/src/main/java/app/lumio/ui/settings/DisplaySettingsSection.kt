package app.lumio.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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
 * The Home section of the settings: what Home shows. Each switch reports its new value at once. The minimum is a numeric field: the owner's
 * entry is saved when the field loses focus or the keyboard's Done is pressed. An entry that is not an amount stays in the field with the
 * reason, and nothing is saved. [notice] shows a save that failed.
 */
@Composable
fun DisplaySettingsSection(
    settings: DisplaySettings,
    notice: String?,
    onShowTotal: (Boolean) -> Unit,
    onShowAssets: (Boolean) -> Unit,
    onMinimumUsd: (Double) -> Unit,
    onHideSpam: (Boolean) -> Unit,
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
    SettingsSwitchRow(
        title = "Hide spam and scam transactions",
        description = "Hides transactions that look like spam or scams.",
        checked = settings.hideSpam,
        onCheckedChange = onHideSpam,
    )
}

/**
 * The minimum balance as a dollar field. The field keeps the owner's text until it is saved or refused. A refused entry shows its reason under
 * the field. An amount that equals the saved one is not saved again.
 */
@Composable
private fun MinimumField(minimumUsd: Double, onMinimumUsd: (Double) -> Unit) {
    var text by remember(minimumUsd) { mutableStateOf(MinimumUsd.format(minimumUsd)) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }

    fun save() {
        val amount = MinimumUsd.parse(text)
        if (amount == null) {
            refusal = "Enter an amount from 0 to 1,000,000, with at most two decimals."
            return
        }
        refusal = null
        text = MinimumUsd.format(amount)
        if (amount != minimumUsd) onMinimumUsd(amount)
    }

    SettingsItem {
        Column(modifier = Modifier.padding(vertical = LenslySpacing.md)) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    refusal = null
                },
                label = { Text("Minimum balance (USD)") },
                supportingText = {
                    Text(refusal ?: "Assets worth less than this are hidden. Assets with no price, such as testnet coins, are always shown.")
                },
                isError = refusal != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state ->
                        if (focused && !state.isFocused) save()
                        focused = state.isFocused
                    },
            )
        }
    }
}
