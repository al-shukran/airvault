package app.lumio.ui.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillStyle

/** Reset on its own line; Cancel and Save share the next. Save is a primary pill and Cancel an outline one. */
@Composable
internal fun SheetButtons(onReset: () -> Unit, onCancel: () -> Unit, onSave: () -> Unit, saveEnabled: Boolean) {
    TextButton(onClick = onReset) {
        Text("Reset to Suggested")
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WalletPill(text = "Cancel", onClick = onCancel, modifier = Modifier.weight(1f), style = WalletPillStyle.Outline)
        WalletPill(
            text = "Save",
            onClick = onSave,
            modifier = Modifier.weight(1f),
            style = WalletPillStyle.Primary,
            enabled = saveEnabled,
        )
    }
}
