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
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The owner's phone is 720 x 1600 px at 280 dpi, which is 411 x 914 dp. The layout is checked in that frame. */
private const val PHONE_DENSITY = 1.75f
private val PHONE_WIDTH: Dp = (720f / PHONE_DENSITY).dp
private val PHONE_HEIGHT: Dp = (1600f / PHONE_DENSITY).dp

/** Gap between two taps that are not the point of a test, so each is an ordinary separate tap. */
private const val SEPARATE_TAP_MS = 1_000L

/** Quick taps: well under a second apart, the way a real pair of rolls can come in. */
private const val QUICK_TAP_MS = 50L

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

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class DiceEntryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** Every symbol the screen handed to onSymbol, in order. */
    private val recorded = mutableListOf<Char>()

    /** Shows the entry for [mode] with its own state: [recorded] gets each symbol and Undo removes the last one. */
    private fun showEntry(mode: EntropyMode) {
        compose.setPhone {
            var entered by remember { mutableStateOf("") }
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
            )
        }
    }

    /** Taps the node with [tag]. Each tap is its own event, so nothing is dropped as a repeat. */
    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performClick()
    }

    @Test
    fun eachDiceButtonEntersItsValue() {
        showEntry(EntropyMode.Dice)

        for (face in '1'..'6') {
            compose.mainClock.advanceTimeBy(SEPARATE_TAP_MS)
            tap(faceTag(face))
        }

        assertEquals(listOf('1', '2', '3', '4', '5', '6'), recorded)
    }

    @Test
    fun headsAndTailsEnterTheirSide() {
        showEntry(EntropyMode.Coins)

        tap(faceTag('H'))
        compose.mainClock.advanceTimeBy(SEPARATE_TAP_MS)
        tap(faceTag('T'))

        assertEquals(listOf('H', 'T'), recorded)
    }

    @Test
    fun undoRemovesTheLastEntry() {
        showEntry(EntropyMode.Dice)
        tap(faceTag('1'))
        tap(faceTag('2'))
        tap(faceTag('3'))
        compose.onNodeWithTag(RECENT_STRIP_TAG).assertTextEquals("123")

        compose.mainClock.advanceTimeBy(SEPARATE_TAP_MS)
        tap(UNDO_TAG)

        compose.onNodeWithTag(RECENT_STRIP_TAG).assertTextEquals("12")
    }

    @Test
    fun sixThenSixTappedFiftyMsApartRecordsTwoEntries() {
        showEntry(EntropyMode.Dice)

        tap(faceTag('6'))
        compose.mainClock.advanceTimeBy(QUICK_TAP_MS)
        tap(faceTag('6'))

        assertEquals(listOf('6', '6'), recorded)
        compose.onNodeWithTag(RECENT_STRIP_TAG).assertTextEquals("66")
    }

    @Test
    fun threeQuickTapsOnTheSameFaceRecordThree() {
        showEntry(EntropyMode.Dice)

        repeat(3) {
            tap(faceTag('3'))
            compose.mainClock.advanceTimeBy(QUICK_TAP_MS)
        }

        assertEquals(listOf('3', '3', '3'), recorded)
        compose.onNodeWithTag(RECENT_STRIP_TAG).assertTextEquals("333")
    }

    @Test
    fun twoQuickUndoTapsRemoveTwoEntries() {
        showEntry(EntropyMode.Dice)
        tap(faceTag('1'))
        tap(faceTag('2'))
        tap(faceTag('3'))
        tap(faceTag('4'))

        tap(UNDO_TAG)
        compose.mainClock.advanceTimeBy(QUICK_TAP_MS)
        tap(UNDO_TAG)

        compose.onNodeWithTag(RECENT_STRIP_TAG).assertTextEquals("12")
    }

    @Test
    fun theLastFiveEntriesAreShownNewestAtTheRightAndSpokenInOrder() {
        showEntry(EntropyMode.Dice)
        for (face in "123456") {
            compose.mainClock.advanceTimeBy(SEPARATE_TAP_MS)
            tap(faceTag(face))
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
    fun aDieButtonIsAtLeast88dpTallAndDiceAreSeparatedByAtLeast12dp() {
        showEntry(EntropyMode.Dice)
        val bounds = "123456".associateWith { compose.onNodeWithTag(faceTag(it)).getBoundsInRoot() }

        for ((face, box) in bounds) {
            assertTrue(box.tall >= MIN_DIE_HEIGHT, "die $face is ${box.tall} tall")
            assertTrue(box.bottom <= PHONE_HEIGHT, "die $face runs off the bottom of the phone")
        }
        for (row in listOf("123", "456")) {
            for ((left, right) in row.zipWithNext()) {
                val gap = bounds.getValue(right).left - bounds.getValue(left).right
                assertTrue(gap >= MIN_DIE_GAP, "gap between $left and $right is $gap")
            }
        }
        val rowGap = bounds.getValue('4').top - bounds.getValue('1').bottom
        assertTrue(rowGap >= MIN_DIE_GAP, "gap between the rows is $rowGap")
    }

    @Test
    fun coinButtonsAreAtLeast120dpTallWithA16dpGap() {
        showEntry(EntropyMode.Coins)
        val heads: DpRect = compose.onNodeWithTag(faceTag('H')).getBoundsInRoot()
        val tails: DpRect = compose.onNodeWithTag(faceTag('T')).getBoundsInRoot()

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
