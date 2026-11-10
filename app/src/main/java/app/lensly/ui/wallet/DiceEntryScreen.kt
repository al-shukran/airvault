package app.lensly.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.RoundKey
import app.lensly.ui.theme.LenslyOnSurface
import kotlin.math.roundToInt

/** How many of the newest entries the dot-matrix strip shows. */
private const val RECENT_ENTRIES = 20

/**
 * Dice or coin entry for the seed ceremony. The owner taps one face at a time. [entered] is the full
 * record so far, and [onSymbol] is called with one more face. Taps are never refused for reaching a
 * count; only [EntropyTargets.MAX_SYMBOLS] stops them. DONE enables at [minimumCount], and
 * [fullStrengthCount] is where the entry reaches its full 256 bits.
 */
@Composable
fun DiceEntryScreen(
    mode: EntropyMode,
    entered: String,
    minimumCount: Int,
    fullStrengthCount: Int,
    onSymbol: (Char) -> Unit,
    onBackspace: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = entropyProgress(mode, entered.length, minimumCount, fullStrengthCount)
    val complete = entered.length >= minimumCount
    val atCap = entered.length >= EntropyTargets.MAX_SYMBOLS
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
        DotText(text = "${entered.length}", fontSize = 40.sp)
        Spacer(Modifier.height(4.dp))
        DotText(text = "${progress.bits.roundToInt()} BITS", fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (entered.isNotEmpty()) {
                DotText(text = recentStrip(entered), fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        CountBar(fraction = progress.fraction)
        Spacer(Modifier.height(8.dp))
        when (progress.status) {
            EntropyStatus.BelowMinimum -> WalletEyebrow("At least $minimumCount $unit")
            EntropyStatus.MinimumReached ->
                WalletEyebrow("Minimum reached · keep going for $FULL_STRENGTH_BITS bits")
            EntropyStatus.FullStrength -> {
                FullStrengthChip()
                Spacer(Modifier.height(8.dp))
                WalletEyebrow("You may continue or tap done")
            }
        }
        Spacer(Modifier.height(32.dp))
        KeyPad(mode = mode, atCap = atCap, onSymbol = onSymbol)
    }
}

/** The newest [RECENT_ENTRIES] entries, with a leading ellipsis when older entries are hidden. */
private fun recentStrip(entered: String): String {
    val tail = entered.takeLast(RECENT_ENTRIES)
    return if (entered.length > RECENT_ENTRIES) "…$tail" else tail
}

/** Outlined chip shown once the entry reaches full strength. */
@Composable
private fun FullStrengthChip() {
    Box(
        modifier = Modifier
            .border(1.dp, LenslyOnSurface, RoundedCornerShape(percent = 50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = "FULL $FULL_STRENGTH_BITS-BIT",
            style = MaterialTheme.typography.labelSmall,
            color = LenslyOnSurface,
        )
    }
}

/** The dice faces 1 to 6 in two rows of three, or the coin sides H and T in one row. */
@Composable
private fun KeyPad(
    mode: EntropyMode,
    atCap: Boolean,
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
                            if (!atCap && mode.accepts(symbol)) onSymbol(symbol)
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
