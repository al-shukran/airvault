package app.lensly.ui.wallet

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.DotHeadline

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
    WalletScaffold(
        modifier = modifier,
        footer = {
            WalletPill(
                text = "I compared them",
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                style = WalletPillStyle.Primary,
            )
        },
    ) {
        Spacer(Modifier.height(24.dp))
        WalletEyebrow("Ceremony result")
        Spacer(Modifier.height(12.dp))
        DotHeadline(text = "COMPARE")
        Spacer(Modifier.height(16.dp))
        Text(
            text = "The first hash was fixed before your rolls. The second proves the mix used them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        WalletEyebrow("Pre-commitment")
        Spacer(Modifier.height(6.dp))
        HashLines(hex = commitmentHex)
        Spacer(Modifier.height(16.dp))
        WalletEyebrow("Mixed verification hash")
        Spacer(Modifier.height(6.dp))
        HashLines(hex = mixedHashHex)
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WalletEyebrow("Checksum word")
            Spacer(Modifier.width(12.dp))
            DotText(text = checksumWord.uppercase(), fontSize = 28.sp)
        }
        Spacer(Modifier.height(8.dp))
    }
}
