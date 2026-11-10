package app.lensly.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

/**
 * Warning shown before the owner skips the ceremony. The wallet then trusts only the device's random
 * generator and is labelled DEVICE-GENERATED. Back is the primary choice; skipping is the red action.
 */
@Composable
fun SkipWarningScreen(
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WalletScaffold(
        modifier = modifier,
        footer = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WalletPill(
                    text = "Back",
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                    style = WalletPillStyle.Primary,
                )
                WalletPill(
                    text = "Skip anyway",
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                    style = WalletPillStyle.Accent,
                )
            }
        },
    ) {
        Spacer(Modifier.height(24.dp))
        WalletEyebrow("Seed ceremony. Skip")
        Spacer(Modifier.height(12.dp))
        DotHeadline(text = "NO MIXING")
        Spacer(Modifier.height(16.dp))
        WalletWarningChip(text = "Device-generated")
        Spacer(Modifier.height(24.dp))
        Text(
            text = "This wallet will trust only the device's random generator.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "It will be labelled DEVICE-GENERATED. Your own rolls and flips will not be used.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
