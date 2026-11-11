package app.lensly.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslySpacing

/**
 * The write-down of a wallet with a passphrase, the last step before the wallet is saved. The passphrase is part of
 * the only backup, so the owner confirms it is written down separately from the 24 words. CREATE WALLET stays off
 * until then.
 */
@Composable
fun PassphraseBackupScreen(
    writtenDown: Boolean,
    onWrittenDownChange: (Boolean) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(
        title = "Passphrase backup",
        modifier = modifier,
        bottomAction = {
            Column(verticalArrangement = Arrangement.spacedBy(LenslySpacing.sm)) {
                CopiedCheck(
                    checked = writtenDown,
                    onCheckedChange = onWrittenDownChange,
                    label = "I wrote the passphrase down separately",
                )
                PillButton(
                    text = "Create wallet",
                    onClick = onCreate,
                    accent = true,
                    enabled = writtenDown,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        Spacer(Modifier.height(LenslySpacing.lg))
        WalletEyebrow("Part of the backup")
        Spacer(Modifier.height(LenslySpacing.xs))
        DotHeadline(text = "WRITE DOWN")
        Spacer(Modifier.height(LenslySpacing.sm))
        InlineBanner(message = "Write it down separately from the 24 words.", tone = BannerTone.Warning)
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = "The 24 words alone restore a different, empty wallet. The encrypted copy on this device is not a " +
                "backup. Write the passphrase down now.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.md))
    }
}
