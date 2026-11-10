package app.lensly.ui.wallet

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class EntropyProgressTest {

    @Test
    fun diceBitsAreCountTimesLog2OfSix() {
        val progress = entropyProgress(EntropyMode.Dice, 73)
        assertTrue(abs(progress.bits - 73 * log2(6.0)) < 1e-9)
        assertEquals(189, progress.bits.roundToInt())
    }

    @Test
    fun coinBitsAreExactlyTheCount() {
        assertEquals(200.0, entropyProgress(EntropyMode.Coins, 200).bits)
    }

    @Test
    fun fractionFillsToTwoHundredFiftySixBitsAndNeverExceedsOne() {
        assertEquals(0f, entropyProgress(EntropyMode.Coins, 0).fraction)
        assertEquals(0.5f, entropyProgress(EntropyMode.Coins, 128).fraction)
        assertEquals(1f, entropyProgress(EntropyMode.Coins, 256).fraction)
        assertEquals(1f, entropyProgress(EntropyMode.Coins, 5_000).fraction)
        assertEquals(1f, entropyProgress(EntropyMode.Dice, 400).fraction)
    }

    @Test
    fun diceStatusChangesAtFiftyAndNinetyNine() {
        assertEquals(EntropyStatus.BelowMinimum, entropyProgress(EntropyMode.Dice, 49).status)
        assertEquals(EntropyStatus.MinimumReached, entropyProgress(EntropyMode.Dice, 50).status)
        assertEquals(EntropyStatus.MinimumReached, entropyProgress(EntropyMode.Dice, 98).status)
        assertEquals(EntropyStatus.FullStrength, entropyProgress(EntropyMode.Dice, 99).status)
        assertEquals(EntropyStatus.FullStrength, entropyProgress(EntropyMode.Dice, 500).status)
    }

    @Test
    fun coinStatusChangesAtOneTwentyEightAndTwoFiftySix() {
        assertEquals(EntropyStatus.BelowMinimum, entropyProgress(EntropyMode.Coins, 127).status)
        assertEquals(EntropyStatus.MinimumReached, entropyProgress(EntropyMode.Coins, 128).status)
        assertEquals(EntropyStatus.MinimumReached, entropyProgress(EntropyMode.Coins, 255).status)
        assertEquals(EntropyStatus.FullStrength, entropyProgress(EntropyMode.Coins, 256).status)
    }

    @Test
    fun explicitCountsOverrideTheTargets() {
        val progress = entropyProgress(EntropyMode.Coins, 10, minimumCount = 10, fullStrengthCount = 20)
        assertEquals(EntropyStatus.MinimumReached, progress.status)
    }

    @Test
    fun targetsMatchTheOwnerSpec() {
        assertEquals(50, EntropyTargets.minimumFor(EntropyMode.Dice))
        assertEquals(99, EntropyTargets.fullStrengthFor(EntropyMode.Dice))
        assertEquals(128, EntropyTargets.minimumFor(EntropyMode.Coins))
        assertEquals(256, EntropyTargets.fullStrengthFor(EntropyMode.Coins))
        assertEquals(10_000, EntropyTargets.MAX_SYMBOLS)
    }
}
