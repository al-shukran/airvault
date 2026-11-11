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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
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
import kotlin.test.assertFalse
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

/** Gap between two taps that the owner makes on purpose, well over the double-tap window. */
private const val SEPARATE_TAP_MS = 1_000L

/** A double tap: a second tap on the same button this soon after the first. */
private const val DOUBLE_TAP_GAP_MS = 100L

/** Two taps this far apart on one button both count. */
private const val SEPARATE_TAP_GAP_MS = 200L

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

    /** The clock the taps are timed by. Each test moves it between taps. */
    private var nowMs = 0L

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
                nowMs = { nowMs },
            )
        }
    }

    /** Taps the node with [tag], after the owner has waited long enough for a tap to count on its own. */
    private fun tap(tag: String) {
        nowMs += SEPARATE_TAP_MS
        compose.onNodeWithTag(tag).performClick()
    }

    @Test
    fun eachDiceButtonEntersItsValue() {
        showEntry(EntropyMode.Dice)

        for (face in '1'..'6') tap(faceTag(face))

        assertEquals(listOf('1', '2', '3', '4', '5', '6'), recorded)
    }

    @Test
    fun headsAndTailsEnterTheirSide() {
        showEntry(EntropyMode.Coins)

        tap(faceTag('H'))
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

        tap(UNDO_TAG)

        compose.onNodeWithTag(RECENT_STRIP_TAG).assertTextEquals("12")
    }

    @Test
    fun aDoubleTapWithinTheWindowEntersOnce() {
        showEntry(EntropyMode.Dice)
        nowMs = SEPARATE_TAP_MS
        compose.onNodeWithTag(faceTag('3')).performClick()
        nowMs += DOUBLE_TAP_GAP_MS
        compose.onNodeWithTag(faceTag('3')).performClick()

        assertEquals(listOf('3'), recorded)
    }

    @Test
    fun twoTapsTwoHundredMsApartEnterTwice() {
        showEntry(EntropyMode.Dice)
        nowMs = SEPARATE_TAP_MS
        compose.onNodeWithTag(faceTag('3')).performClick()
        nowMs += SEPARATE_TAP_GAP_MS
        compose.onNodeWithTag(faceTag('3')).performClick()

        assertEquals(listOf('3', '3'), recorded)
    }

    @Test
    fun aDoubleTapOnUndoRemovesOnlyOneEntry() {
        showEntry(EntropyMode.Dice)
        tap(faceTag('1'))
        tap(faceTag('2'))
        tap(faceTag('3'))

        tap(UNDO_TAG)
        nowMs += DOUBLE_TAP_GAP_MS
        compose.onNodeWithTag(UNDO_TAG).performClick()

        compose.onNodeWithTag(RECENT_STRIP_TAG).assertTextEquals("12")
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

/** The double-tap window: a tap inside it is ignored, and one at or past it counts. */
class DoubleTapGuardTest {

    @Test
    fun aTapInsideTheWindowIsIgnoredAndOneAtTheWindowCounts() {
        val guard = DoubleTapGuard<Any>()

        assertTrue(guard.accept('1', 0L))
        assertFalse(guard.accept('1', DOUBLE_TAP_WINDOW_MS - 1))
        assertTrue(guard.accept('1', DOUBLE_TAP_WINDOW_MS))
    }

    @Test
    fun aRejectedTapDoesNotRestartTheWindow() {
        val guard = DoubleTapGuard<Any>()

        assertTrue(guard.accept('1', 0L))
        assertFalse(guard.accept('1', 100L))
        assertTrue(guard.accept('1', 200L))
    }

    @Test
    fun tapsOnDifferentButtonsNeverBlockEachOther() {
        val guard = DoubleTapGuard<Any>()

        assertTrue(guard.accept('1', 0L))
        assertTrue(guard.accept('2', 10L))
        assertFalse(guard.accept('1', 10L))
    }

    @Test
    fun theWindowIsOneHundredAndFiftyMilliseconds() {
        assertEquals(150L, DOUBLE_TAP_WINDOW_MS)
    }
}
