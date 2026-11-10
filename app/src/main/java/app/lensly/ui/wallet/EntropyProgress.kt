package app.lensly.ui.wallet

import kotlin.math.log2
import kotlin.math.min

/** Bits the entropy entry aims for. The progress bar fills to this. */
const val FULL_STRENGTH_BITS = 256

/** Where an entropy entry stands against its minimum and full-strength counts. */
enum class EntropyStatus { BelowMinimum, MinimumReached, FullStrength }

/**
 * Progress of an entropy entry. [bits] is exact: a dice roll gives log2(6) bits and a coin flip gives
 * one. [fraction] fills the progress bar and never exceeds 1.
 */
data class EntropyProgress(
    val count: Int,
    val bits: Double,
    val fraction: Float,
    val status: EntropyStatus,
)

/**
 * Progress for [count] symbols in [mode]. The counts default to [EntropyTargets] for the mode.
 */
fun entropyProgress(
    mode: EntropyMode,
    count: Int,
    minimumCount: Int = EntropyTargets.minimumFor(mode),
    fullStrengthCount: Int = EntropyTargets.fullStrengthFor(mode),
): EntropyProgress {
    val bits = count * bitsPerSymbol(mode)
    val fraction = min(1.0, bits / FULL_STRENGTH_BITS).toFloat()
    val status = when {
        count >= fullStrengthCount -> EntropyStatus.FullStrength
        count >= minimumCount -> EntropyStatus.MinimumReached
        else -> EntropyStatus.BelowMinimum
    }
    return EntropyProgress(count, bits, fraction, status)
}

private fun bitsPerSymbol(mode: EntropyMode): Double = when (mode) {
    EntropyMode.Dice -> log2(6.0)
    EntropyMode.Coins -> 1.0
}
