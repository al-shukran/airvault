package app.lensly.ui.wallet

/**
 * Entry counts for the dice and coin entropy ceremony. [DICE_MINIMUM] and [COIN_MINIMUM] enable DONE.
 * [DICE_FULL_STRENGTH] and [COIN_FULL_STRENGTH] are where the entry reaches 256 bits. Taps are never
 * refused for reaching a count, so the owner may keep going past full strength.
 */
object EntropyTargets {
    const val DICE_MINIMUM = 50
    const val DICE_FULL_STRENGTH = 99
    const val COIN_MINIMUM = 128
    const val COIN_FULL_STRENGTH = 256

    /** Upper bound on recorded symbols, so a runaway tap loop cannot exhaust memory. Taps past it are ignored. */
    const val MAX_SYMBOLS = 10_000

    fun minimumFor(mode: EntropyMode): Int = when (mode) {
        EntropyMode.Dice -> DICE_MINIMUM
        EntropyMode.Coins -> COIN_MINIMUM
    }

    fun fullStrengthFor(mode: EntropyMode): Int = when (mode) {
        EntropyMode.Dice -> DICE_FULL_STRENGTH
        EntropyMode.Coins -> COIN_FULL_STRENGTH
    }
}
