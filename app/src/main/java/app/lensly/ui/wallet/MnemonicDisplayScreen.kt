package app.lensly.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceContainer

/** Words per row of the grid. The grid reads left to right, then down. */
private const val GRID_COLUMNS = 2

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
    LenslyScreen(
        title = "Recovery phrase",
        modifier = modifier,
        bottomAction = {
            WalletPill(
                text = "I wrote it down",
                onClick = onWrittenDown,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Primary,
            )
        },
    ) {
        WalletEyebrow("${words.size} words, in order")
        Spacer(Modifier.height(LenslySpacing.xs))
        // One line at 48sp, so the grid of 24 words fits above the button on a 720x1600 screen.
        DotText(text = "WRITE DOWN", fontSize = 48.sp)
        Spacer(Modifier.height(LenslySpacing.lg))
        InlineBanner(
            title = "Never photograph",
            message = "Anyone with these words can spend from this wallet. Write them down by hand.",
            tone = BannerTone.Warning,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        WordGrid(words = words)
        Spacer(Modifier.height(LenslySpacing.sm))
    }
}

@Composable
private fun WordGrid(words: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
        words.chunked(GRID_COLUMNS).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
            ) {
                row.forEachIndexed { column, word ->
                    WordCell(
                        number = rowIndex * GRID_COLUMNS + column + 1,
                        word = word,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * One word on its own tile. The number sits in a fixed-width column, so the words start at the same x in every row of
 * a column, and the tiles are equal width.
 */
@Composable
private fun WordCell(
    number: Int,
    word: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(LenslyRadius.md)
    Row(
        modifier = modifier
            .height(CELL_HEIGHT)
            .clip(shape)
            .background(LenslySurfaceContainer, shape)
            .padding(horizontal = LenslySpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(NUMBER_WIDTH)) {
            Text(
                text = "%02d".format(number),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = word.uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

private val CELL_HEIGHT = 40.dp
private val CELL_GAP = 6.dp
private val NUMBER_WIDTH = 24.dp
