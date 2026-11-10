package app.lensly.ui.gate

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillStyle
import app.lensly.ui.wallet.WalletScaffold

/**
 * Neutral message for when the vault cannot be opened on this device. It does not say why, so it gives nothing away
 * about the vault.
 */
@Composable
fun UnavailableScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WalletScaffold(
        modifier = modifier,
        scrollable = false,
        footer = {
            WalletPill(
                text = "Close",
                onClick = onClose,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                style = WalletPillStyle.Primary,
            )
        },
    ) {
        Spacer(Modifier.height(24.dp))
        DotHeadline(text = "NOT AVAILABLE")
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Lensly cannot open on this device.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
