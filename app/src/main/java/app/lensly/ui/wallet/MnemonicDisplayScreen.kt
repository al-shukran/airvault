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
import app.lensly.ui.components.DotHeadline

/**
 * Shows the recovery words in a numbered two-column grid, read left to right and then down. Anyone
 * with these words can spend from the wallet, so the screen warns against photographs. The grid
 * scrolls on short screens.
 */
@Composable
fun MnemonicDisplayScreen(
    words: List<String>,
    onWrittenDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WalletScaffold(
        modifier = modifier,
        footer = {
            WalletPill(
                text = "I wrote it down",
                onClick = onWrittenDown,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                style = WalletPillStyle.Primary,
            )
        },
    ) {
        Spacer(Modifier.height(16.dp))
        WalletEyebrow("Recovery phrase. ${words.size} words")
        Spacer(Modifier.height(8.dp))
        DotHeadline(text = "WRITE DOWN")
        Spacer(Modifier.height(12.dp))
        WalletWarningChip(text = "Never photograph")
        Spacer(Modifier.height(20.dp))
        words.chunked(2).forEachIndexed { rowIndex, pair ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pair.forEachIndexed { column, word ->
                    WordCell(
                        number = rowIndex * 2 + column + 1,
                        word = word,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun WordCell(
    number: Int,
    word: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "%02d".format(number),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = word.uppercase(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
