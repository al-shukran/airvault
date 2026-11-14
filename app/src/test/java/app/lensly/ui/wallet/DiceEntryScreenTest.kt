package app.lensly.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import app.lensly.app.HideDelay
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyTheme
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The owner's phone is 720 x 1600 px at 280 dpi, which is 411 x 914 dp. The layout is checked in that frame. */
private const val PHONE_DENSITY = 1.75f
private val PHONE_WIDTH: Dp = (720f / PHONE_DENSITY).dp
private val PHONE_HEIGHT: Dp = (1600f / PHONE_DENSITY).dp

/** The number of values the hide delay is drawn from: 100 + nextInt(901) covers 100 to 1000 ms. */
private const val DELAY_DRAW_BOUND = 901

/** Height of a button's bounds in dp. [DpRect] has edges but no height of its own. */
private val DpRect.tall: Dp get() = bottom - top

/** Minimum size of a die face button, in dp. */
private val MIN_DIE_HEIGHT: Dp = 88.dp

/** Minimum size of a coin button, in dp. */
private val MIN_COIN_HEIGHT: Dp = 120.dp

/** Minimum gap between die faces, in dp. */
private val MIN_DIE_GAP: Dp = 12.dp

/** Gap between the two coin buttons, in dp. */
private val COIN_GAP: Dp = 16.dp

/**
 * A source with fixed draws: every shuffle draw is 0 and the hide-delay draw is [delayDraw]. With every shuffle draw at
 * 0, one tap moves the dice from 1 2 3 4 5 6 to 2 3 4 5 6 1, and the default delay is the shortest, 100 ms.
 */
private class ScriptedRandom(private val delayDraw: Int = 0) : SecureRandom() {
    override fun nextInt(bound: Int): Int = if (bound == DELAY_DRAW_BOUND) delayDraw else 0
}

/**
 * The wait after a tap, held by the test. Each hide records its delay and then stays hidden until [release], so a
 * test decides when the tiles are shown again.
 */
private class HeldHide {
    /** The delay of every hide so far, in order. */
    val delays = mutableListOf<Long>()
    private val releases = Channel<Unit>(Channel.UNLIMITED)

    /** Passed to the screen as its hideFor. */
    val wait: suspend (Long) -> Unit = { ms ->
        delays += ms
        releases.receive()
    }

    /** Lets the pending hide finish, so the tiles are shown again. */
    fun release() {
        releases.trySend(Unit)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class DiceEntryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** Every symbol the screen handed to onSymbol, in order. */
    private val recorded = mutableListOf<Char>()

    /** The wait after each tap. A hide stays until the test calls [reveal]. */
    private val hold = HeldHide()

    /**
     * Shows the entry for [mode] with its own state: [recorded] gets each symbol and Undo removes the last one.
     * [random] decides the shuffles and the hide delay. [hold] decides when the tiles are shown again.
     */
    private fun showEntry(mode: EntropyMode, random: SecureRandom = ScriptedRandom(), initial: String = "") {
        compose.setPhone {
            var entered by remember { mutableStateOf(initial) }
            DiceEntryScreen(
                mode = mode,
                entered = entered,
                minimumCount = EntropyTargets.minimumFor(mode),
                fullStrengthCount = EntropyTargets.fullStrengthFor(mode),
                onSymbol = { symbol ->
                    entered += symbol
                    recorded += symbol
                },
                onBackspace = { entered = entered.dropLast(1) },
                onDone = {},
                random = random,
                hideFor = hold.wait,
            )
        }
    }

    /** Taps the tile labelled [label], e.g. "Roll 3" or "Heads", as one tap. */
    private fun tapTile(label: String) {
        compose.onNodeWithContentDescription(label).performClick()
    }

    private fun tapUndo() {
        compose.onNodeWithTag(UNDO_TAG).performClick()
    }

    /** Lets the pending hide finish, and waits until the tiles have been shown again. */
    private fun reveal() {
        hold.release()
        compose.waitForIdle()
    }

    /** Taps the tile labelled [label], then lets the tiles be shown again. */
    private fun tapAndReveal(label: String) {
        tapTile(label)
        reveal()
    }

    /** Asserts the label of each tile in grid order. */
    private fun assertTileLabels(vararg labels: String) {
        labels.forEachIndexed { index, label ->
            compose.onNodeWithTag(tileTag(index)).assert(hasContentDescription(label))
        }
    }

    @Test
    fun eachDiceTileEntersItsFace() {
        showEntry(EntropyMode.Dice)

        for (face in '1'..'6') {
            tapAndReveal("Roll $face")
        }

        assertEquals(listOf('1', '2', '3', '4', '5', '6'), recorded)
    }

    @Test
    fun headsAndTailsEnterTheirSide() {
        showEntry(EntropyMode.Coins)

        tapAndReveal("Heads")
        tapTile("Tails")

        assertEquals(listOf('H', 'T'), recorded)
    }

    @Test
    fun undoRemovesTheLastEntry() {
        showEntry(EntropyMode.Dice)
        for (face in "123") {
            tapAndReveal("Roll $face")
        }
        compose.onNodeWithTag(RECENT_STRIP_TAG).assertTextEquals("123")

        tapUndo()

        compose.onNodeWithTag(RECENT_STRIP_TAG).assertTextEquals("12")
    }

    @Test
    fun theRecordedFaceIsTheFaceOnTheTappedTileAtTapTime() {
        showEntry(EntropyMode.Dice)
        tapAndReveal("Roll 1")

        // The scripted shuffle left 2 3 4 5 6 1 on the tiles, so tile 0 now shows 2.
        assertTileLabels("Roll 2", "Roll 3", "Roll 4", "Roll 5", "Roll 6", "Roll 1")
        compose.onNodeWithTag(tileTag(0)).performClick()

        assertEquals(listOf('1', '2'), recorded)
        compose.onNodeWithTag(LAST_ENTRIES_TAG).assert(hasContentDescription("Last entries: 1, 2"))
    }

    @Test
    fun theTilesAreHiddenForTheDelayAndThenShowTheShuffledLayout() {
        showEntry(EntropyMode.Dice, random = ScriptedRandom(delayDraw = 0))
        tapTile("Roll 1")
        compose.waitForIdle() // the hide starts on the frame after the tap

        assertEquals(listOf(HideDelay.DEFAULT_MIN_MS), hold.delays)
        for (index in 0..5) {
            compose.onNodeWithTag(tileTag(index)).assert(hasContentDescription("Shuffling"))
        }

        reveal()
        assertTileLabels("Roll 2", "Roll 3", "Roll 4", "Roll 5", "Roll 6", "Roll 1")
    }

    @Test
    fun theLongestDelayIsOneSecondAndKeepsTheTilesHidden() {
        showEntry(EntropyMode.Dice, random = ScriptedRandom(delayDraw = DELAY_DRAW_BOUND - 1))
        tapTile("Roll 1")
        compose.waitForIdle() // the hide starts on the frame after the tap

        assertEquals(listOf(HideDelay.DEFAULT_MAX_MS), hold.delays)
        compose.onNodeWithTag(tileTag(0)).assertIsNotEnabled()

        reveal()
        compose.onNodeWithTag(tileTag(0)).assertIsEnabled()
    }

    @Test
    fun aTapWhileHiddenIsRefusedAndTheTilesAreDisabled() {
        showEntry(EntropyMode.Dice)
        tapTile("Roll 6")

        for (index in 0..5) {
            compose.onNodeWithTag(tileTag(index)).assertIsNotEnabled()
        }
        compose.onNodeWithTag(tileTag(0)).performTouchInput { click() }
        compose.onNodeWithTag(UNDO_TAG).assertIsNotEnabled()

        assertEquals(listOf('6'), recorded)
        compose.onNodeWithTag(RECENT_STRIP_TAG).assertTextEquals("6")
    }

    @Test
    fun doneAndUndoAreDisabledWhileHidden() {
        showEntry(EntropyMode.Dice, initial = "1".repeat(EntropyTargets.DICE_MINIMUM))
        compose.onNodeWithText("DONE").assertIsEnabled()
        compose.onNodeWithTag(UNDO_TAG).assertIsEnabled()

        tapTile("Roll 1")

        compose.onNodeWithText("DONE").assertIsNotEnabled()
        compose.onNodeWithTag(UNDO_TAG).assertIsNotEnabled()

        reveal()
        compose.onNodeWithText("DONE").assertIsEnabled()
        compose.onNodeWithTag(UNDO_TAG).assertIsEnabled()
    }

    @Test
    fun theLastFiveEntriesAreShownNewestAtTheRightAndSpokenInOrder() {
        showEntry(EntropyMode.Dice)
        for (face in "123456") {
            tapAndReveal("Roll $face")
        }

        compose.onNodeWithTag(LAST_ENTRIES_TAG)
            .assert(hasContentDescription("Last entries: 2, 3, 4, 5, 6"))
    }

    @Test
    fun theLastEntriesRowSaysNoEntriesBeforeTheFirstTap() {
        showEntry(EntropyMode.Dice)

        compose.onNodeWithTag(LAST_ENTRIES_TAG).assert(hasContentDescription("No entries yet"))
    }

    @Test
    fun aDieTileIsAtLeast88dpTallAndDiceAreSeparatedByAtLeast12dp() {
        showEntry(EntropyMode.Dice)
        val bounds = (0..5).map { compose.onNodeWithTag(tileTag(it)).getBoundsInRoot() }

        for ((index, box) in bounds.withIndex()) {
            assertTrue(box.tall >= MIN_DIE_HEIGHT, "tile $index is ${box.tall} tall")
            assertTrue(box.bottom <= PHONE_HEIGHT, "tile $index runs off the bottom of the phone")
        }
        // Tiles 0, 1 and 3, 4 sit before a gap within their row.
        for (index in listOf(0, 1, 3, 4)) {
            val gap = bounds[index + 1].left - bounds[index].right
            assertTrue(gap >= MIN_DIE_GAP, "gap after tile $index is $gap")
        }
        val rowGap = bounds[3].top - bounds[0].bottom
        assertTrue(rowGap >= MIN_DIE_GAP, "gap between the rows is $rowGap")
    }

    @Test
    fun coinButtonsAreAtLeast120dpTallWithA16dpGap() {
        showEntry(EntropyMode.Coins)
        val heads: DpRect = compose.onNodeWithTag(tileTag(0)).getBoundsInRoot()
        val tails: DpRect = compose.onNodeWithTag(tileTag(1)).getBoundsInRoot()

        assertTrue(heads.tall >= MIN_COIN_HEIGHT, "heads is ${heads.tall} tall")
        assertTrue(tails.tall >= MIN_COIN_HEIGHT, "tails is ${tails.tall} tall")
        val gap = tails.left - heads.right
        assertTrue(gap >= COIN_GAP, "gap between heads and tails is $gap")
    }

    @Test
    fun theProgressLineUndoAndDoneStayOnTheFrame() {
        showEntry(EntropyMode.Dice)

        compose.onNodeWithText("AT LEAST 50 ROLLS").assertIsDisplayed()
        compose.onNodeWithTag(UNDO_TAG).assertIsDisplayed()
        compose.onNodeWithText("DONE").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag(UNDO_TAG).getBoundsInRoot().bottom <= PHONE_HEIGHT)
    }

    private fun ComposeContentTestRule.setPhone(content: @Composable () -> Unit) {
        setContent {
            LenslyTheme {
                Box(
                    modifier = Modifier
                        .requiredSize(width = PHONE_WIDTH, height = PHONE_HEIGHT)
                        .background(LenslyBlack),
                ) {
                    content()
                }
            }
        }
    }
}
