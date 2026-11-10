package app.lensly.ui.wallet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.DotHeadline

/**
 * Start of the seed ceremony. The device has already committed to its randomness, so the commitment
 * is shown first (tap to expand). The owner then mixes in dice rolls or coin flips, or skips.
 */
@Composable
fun CeremonyChoiceScreen(
    commitmentHex: String,
    onDice: () -> Unit,
    onCoins: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    WalletScaffold(
        modifier = modifier,
        footer = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        WalletPill(
                            text = "Roll dice",
                            onClick = onDice,
                            modifier = Modifier.fillMaxWidth(),
                            style = WalletPillStyle.Primary,
                        )
                        WalletEyebrow("About 50 rolls")
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        WalletPill(
                            text = "Flip coins",
                            onClick = onCoins,
                            modifier = Modifier.fillMaxWidth(),
                            style = WalletPillStyle.Primary,
                        )
                        WalletEyebrow("128 flips")
                    }
                }
                WalletPill(
                    text = "Skip",
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth(),
                    style = WalletPillStyle.Outline,
                )
            }
        },
    ) {
        Spacer(Modifier.height(24.dp))
        WalletEyebrow("Seed ceremony")
        Spacer(Modifier.height(12.dp))
        DotHeadline(text = "MIX IN YOURS")
        Spacer(Modifier.height(16.dp))
        Text(
            text = "The device has already committed to its randomness.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Your rolls or flips are mixed in on top of it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        WalletEyebrow(if (expanded) "Commitment. Tap to collapse" else "Commitment. Tap to expand")
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier.clickable { expanded = !expanded },
        ) {
            if (expanded) {
                HashLines(hex = commitmentHex)
            } else {
                Text(
                    text = previewHex(commitmentHex),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
