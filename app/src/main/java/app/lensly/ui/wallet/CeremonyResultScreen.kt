package app.lensly.ui.wallet

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslySpacing

/**
 * Ceremony result. It shows the commitment fixed before the owner's rolls, the hash after the mix,
 * and the checksum word. The owner confirms they compared the two hashes.
 */
@Composable
fun CeremonyResultScreen(
    commitmentHex: String,
    mixedHashHex: String,
    checksumWord: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(
        title = "Ceremony result",
        modifier = modifier,
        bottomAction = {
            PillButton(
                text = "I compared them",
                onClick = onConfirm,
                accent = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Spacer(Modifier.height(LenslySpacing.lg))
        DotHeadline(text = "COMPARE")
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = "The first hash was fixed before your rolls. The second proves the mix used them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.lg))
        WalletPanel {
            WalletEyebrow("Pre-commitment")
            HashLines(hex = commitmentHex)
        }
        Spacer(Modifier.height(LenslySpacing.sm))
        WalletPanel {
            WalletEyebrow("Mixed verification hash")
            HashLines(hex = mixedHashHex)
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WalletEyebrow("Checksum word", modifier = Modifier.weight(1f))
            DotText(text = checksumWord.uppercase(), fontSize = 28.sp)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}
