package app.lensly.ui.wallet

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.RoundKey
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import kotlin.math.roundToInt

/** How many of the newest entries the dot-matrix strip shows. */
private const val RECENT_ENTRIES = 20

/** Height of the recent strip and the status line. Each has one height, so the keys do not move as the entry grows. */
private val LINE_HEIGHT = 32.dp

/**
 * Dice or coin entry for the seed ceremony. The owner taps one face at a time. [entered] is the full
 * record so far, and [onSymbol] is called with one more face. Taps are never refused for reaching a
 * count; only [EntropyTargets.MAX_SYMBOLS] stops them. DONE enables at [minimumCount], and
 * [fullStrengthCount] is where the entry reaches its full 256 bits. Undo is off until the first face.
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
    LenslyScreen(
        title = if (mode == EntropyMode.Dice) "Roll the dice" else "Flip the coins",
        modifier = modifier,
        scrollable = false,
        bottomAction = {
            Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm)) {
                PillButton(
                    text = "Undo",
                    onClick = onBackspace,
                    enabled = entered.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    text = "Done",
                    onClick = onDone,
                    accent = true,
                    enabled = complete,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) {
        Spacer(Modifier.height(LenslySpacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xl)) {
            Stat(label = unit, value = "${entered.length}")
            Stat(label = "Bits of $FULL_STRENGTH_BITS", value = "${progress.bits.roundToInt()}")
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        WalletEyebrow("Recent")
        Spacer(Modifier.height(LenslySpacing.xs))
        Box(
            modifier = Modifier.fillMaxWidth().height(LINE_HEIGHT),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (entered.isEmpty()) {
                Text(
                    text = "Tap a face to start.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                DotText(text = recentStrip(entered), fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(LenslySpacing.md))
        ProgressTrack(fraction = progress.fraction)
        Spacer(Modifier.height(LenslySpacing.sm))
        EntryStatus(status = progress.status, minimumCount = minimumCount, unit = unit)

        Spacer(Modifier.weight(1f))

        KeyPad(mode = mode, atCap = atCap, onSymbol = onSymbol)
        Spacer(Modifier.height(LenslySpacing.md))
    }
}

/** One figure in the header: a muted label over a dot-matrix number. */
@Composable
private fun Stat(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(LenslySpacing.xxs)) {
        WalletEyebrow(label)
        DotText(text = value, fontSize = 40.sp)
    }
}

/** What the entry needs next: how many more symbols reach DONE, or that it is full strength. */
@Composable
private fun EntryStatus(status: EntropyStatus, minimumCount: Int, unit: String) {
    Row(
        modifier = Modifier.fillMaxWidth().height(LINE_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
    ) {
        when (status) {
            EntropyStatus.BelowMinimum -> WalletEyebrow("At least $minimumCount $unit")
            EntropyStatus.MinimumReached ->
                WalletEyebrow("Minimum reached · keep going for $FULL_STRENGTH_BITS bits")
            EntropyStatus.FullStrength -> {
                FullStrengthChip()
                WalletEyebrow("You may continue or tap done")
            }
        }
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
            .padding(horizontal = LenslySpacing.sm, vertical = LenslySpacing.xxs + 2.dp),
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
        verticalArrangement = Arrangement.spacedBy(LenslySpacing.md),
    ) {
        mode.symbols.toList().chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.md, Alignment.CenterHorizontally)) {
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
