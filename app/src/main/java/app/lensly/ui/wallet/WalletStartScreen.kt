package app.lensly.ui.wallet

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.PillChip

/**
 * First screen of the wallet flow: create a new wallet, or restore one from its 24 words. The caller
 * decides what each choice starts.
 */
@Composable
fun WalletStartScreen(
    onCreate: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WalletScaffold(modifier = modifier) {
        Spacer(Modifier.height(24.dp))
        WalletEyebrow("Offline vault")
        Spacer(Modifier.height(12.dp))
        DotHeadline(text = "NEW WALLET")
        Spacer(Modifier.height(16.dp))
        PillChip(text = "24-word recovery phrase")
        Spacer(Modifier.height(48.dp))
        WalletPill(
            text = "Create",
            onClick = onCreate,
            modifier = Modifier.fillMaxWidth(),
            style = WalletPillStyle.Primary,
            size = WalletPillSize.Large,
        )
        Spacer(Modifier.height(16.dp))
        WalletPill(
            text = "Restore",
            onClick = onRestore,
            modifier = Modifier.fillMaxWidth(),
            size = WalletPillSize.Large,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Nothing leaves this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
