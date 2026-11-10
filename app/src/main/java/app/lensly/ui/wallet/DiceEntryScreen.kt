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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.RoundKey
import app.lensly.ui.theme.LenslyOnSurface

/** How many of the newest entries the dot-matrix strip shows. */
private const val RECENT_ENTRIES = 12

/**
 * Dice or coin entry for the seed ceremony. The owner taps one face at a time. [entered] is the full
 * record so far, and [onSymbol] is called with one more face. Taps stop at [targetCount], and DONE
 * only enables once the target is reached.
 */
@Composable
fun DiceEntryScreen(
    mode: EntropyMode,
    entered: String,
    targetCount: Int,
    onSymbol: (Char) -> Unit,
    onBackspace: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val complete = entered.length >= targetCount
    val fraction = if (targetCount > 0) entered.length.toFloat() / targetCount else 0f
    val unit = if (mode == EntropyMode.Dice) "rolls" else "flips"
    WalletScaffold(
        modifier = modifier,
        footer = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WalletPill(
                    text = "Undo",
                    onClick = onBackspace,
                    modifier = Modifier.weight(1f),
                    style = WalletPillStyle.Outline,
                )
                WalletPill(
                    text = "Done",
                    onClick = onDone,
                    modifier = Modifier.weight(1f),
                    style = WalletPillStyle.Primary,
                    enabled = complete,
                )
            }
        },
    ) {
        Spacer(Modifier.height(24.dp))
        WalletEyebrow(if (mode == EntropyMode.Dice) "Roll the dice" else "Flip the coins")
        Spacer(Modifier.height(8.dp))
        DotText(text = "${entered.length} / $targetCount", fontSize = 40.sp)
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (entered.isNotEmpty()) {
                DotText(
                    text = entered.takeLast(RECENT_ENTRIES).toList().joinToString(" "),
                    fontSize = 24.sp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        CountBar(fraction = fraction)
        Spacer(Modifier.height(8.dp))
        WalletEyebrow(if (complete) "Complete. Tap done." else "Enter $targetCount $unit")
        Spacer(Modifier.height(32.dp))
        KeyPad(mode = mode, complete = complete, onSymbol = onSymbol)
    }
}

/** The dice faces 1 to 6 in two rows of three, or the coin sides H and T in one row. */
@Composable
private fun KeyPad(
    mode: EntropyMode,
    complete: Boolean,
    onSymbol: (Char) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        mode.symbols.toList().chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                row.forEach { symbol ->
                    RoundKey(
                        label = symbol.toString(),
                        sublabel = coinSublabel(mode, symbol),
                        onClick = {
                            if (!complete && mode.accepts(symbol)) onSymbol(symbol)
                        },
                    )
                }
            }
        }
    }
}

private fun coinSublabel(mode: EntropyMode, symbol: Char): String? = when {
    mode != EntropyMode.Coins -> null
    symbol == 'H' -> "HEADS"
    else -> "TAILS"
}

/** Thin progress bar: white fill over a surface track. */
@Composable
private fun CountBar(fraction: Float) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(colors.surfaceVariant, CircleShape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .background(LenslyOnSurface, CircleShape),
        )
    }
}
