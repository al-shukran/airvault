package app.lensly.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.wallet.CeremonyChoiceScreen
import app.lensly.ui.wallet.CeremonyResultScreen
import app.lensly.ui.wallet.DiceEntryScreen
import app.lensly.ui.wallet.EntropyMode
import app.lensly.ui.wallet.EntropyTargets
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Seed ceremony: the choice with the commitment folded and open, the result, and dice or coin entry at each stage. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CeremonyScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun ceremonyChoice() = compose.snapshot("ceremony_choice") {
        CeremonyChoiceScreen(commitmentHex = COMMITMENT, onDice = {}, onCoins = {}, onSkip = {})
    }

    @Test
    fun ceremonyChoiceExpanded() = compose.snapshot("ceremony_choice_expanded") {
        CeremonyChoiceScreen(
            commitmentHex = COMMITMENT,
            onDice = {},
            onCoins = {},
            onSkip = {},
            initiallyExpanded = true,
        )
    }

    @Test
    fun ceremonyResult() = compose.snapshot("ceremony_result") {
        CeremonyResultScreen(
            commitmentHex = COMMITMENT,
            mixedHashHex = MIXED,
            checksumWord = "river",
            onConfirm = {},
        )
    }

    @Test
    fun diceEmpty() = compose.snapshot("dice_entry_empty") {
        dice(EntropyMode.Dice, count = 0)
    }

    @Test
    fun diceBelowMinimum() = compose.snapshot("dice_entry_below_minimum") {
        dice(EntropyMode.Dice, count = 20)
    }

    @Test
    fun diceMinimumReached() = compose.snapshot("dice_entry_minimum_reached") {
        dice(EntropyMode.Dice, count = 60)
    }

    @Test
    fun diceFullStrength() = compose.snapshot("dice_entry_full_strength") {
        dice(EntropyMode.Dice, count = EntropyTargets.DICE_FULL_STRENGTH)
    }

    @Test
    fun coinsEmpty() = compose.snapshot("coin_entry_empty") {
        dice(EntropyMode.Coins, count = 0)
    }

    @Test
    fun coinsMinimumReached() = compose.snapshot("coin_entry_minimum_reached") {
        dice(EntropyMode.Coins, count = 140)
    }
}

private val COMMITMENT = "9c1e4b7a2d8f03e5".repeat(4)
private val MIXED = "e5d4c3b2a1908f7e".repeat(4)

/** A deterministic record of [count] faces, so the golden does not change between runs. */
private fun symbols(mode: EntropyMode, count: Int): String = buildString {
    repeat(count) { append(mode.symbols[it % mode.symbols.length]) }
}

@Composable
private fun dice(mode: EntropyMode, count: Int) {
    DiceEntryScreen(
        mode = mode,
        entered = symbols(mode, count),
        minimumCount = EntropyTargets.minimumFor(mode),
        fullStrengthCount = EntropyTargets.fullStrengthFor(mode),
        onSymbol = {},
        onBackspace = {},
        onDone = {},
    )
}
