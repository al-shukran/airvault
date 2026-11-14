package app.lensly.ui.wallet

import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.lensly.app.HideDelay
import org.junit.Test

/** A SecureRandom that records every bound it is asked for and answers each draw with [draw]. */
private class RecordingRandom(private val draw: (Int) -> Int = { 0 }) : SecureRandom() {
    val bounds = mutableListOf<Int>()

    override fun nextInt(bound: Int): Int {
        bounds += bound
        return draw(bound)
    }
}

class EntryTilesTest {

    @Test
    fun aTapReturnsTheFaceOnThatTileBeforeTheShuffle() {
        val tiles = EntryTiles(EntropyMode.Dice, RecordingRandom())

        assertEquals('4', tiles.tap(3))
        // With every draw at 0 the shuffle moves 1 2 3 4 5 6 to 2 3 4 5 6 1.
        assertEquals("234561".toList(), tiles.faces)
    }

    @Test
    fun aTapWhileHiddenIsRefusedAndChangesNothing() {
        val tiles = EntryTiles(EntropyMode.Coins, RecordingRandom())
        assertEquals('H', tiles.tap(0))
        val hiddenFaces = tiles.faces

        assertNull(tiles.tap(0))
        assertNull(tiles.tap(1))
        assertFalse(tiles.hide())

        assertTrue(tiles.hidden)
        assertEquals(hiddenFaces, tiles.faces)
    }

    @Test
    fun revealShowsTheShuffledLayoutAndTheNextTapIsRecorded() {
        val tiles = EntryTiles(EntropyMode.Dice, RecordingRandom())
        tiles.tap(0)

        tiles.reveal()

        assertFalse(tiles.hidden)
        assertEquals('2', tiles.tap(0))
    }

    @Test
    fun theShuffleIsFisherYatesDrawingEachPositionFromTheTilesAtOrBeforeIt() {
        val random = RecordingRandom()

        EntryTiles(EntropyMode.Dice, random).tap(0)

        assertEquals(listOf(6, 5, 4, 3, 2), random.bounds)
    }

    @Test
    fun theCoinShuffleDrawsOnceFromTwoTiles() {
        val random = RecordingRandom()

        EntryTiles(EntropyMode.Coins, random).tap(0)

        assertEquals(listOf(2), random.bounds)
    }

    @Test
    fun theDefaultHideDelayIsOneHundredPlusNextIntOf901() {
        val shortest = RecordingRandom()
        assertEquals(HideDelay.DEFAULT_MIN_MS, EntryTiles(EntropyMode.Dice, shortest).hideDelayMs())
        assertEquals(listOf(901), shortest.bounds)

        val longest = RecordingRandom { 900 }
        assertEquals(HideDelay.DEFAULT_MAX_MS, EntryTiles(EntropyMode.Dice, longest).hideDelayMs())
    }

    @Test
    fun aSavedRangeDrawsFromItsOwnBounds() {
        val range = HideDelay(250, 4_000)
        val shortest = RecordingRandom()
        assertEquals(250L, EntryTiles(EntropyMode.Dice, shortest, range).hideDelayMs())
        assertEquals(listOf(3_751), shortest.bounds)

        val longest = RecordingRandom { 3_750 }
        assertEquals(4_000L, EntryTiles(EntropyMode.Dice, longest, range).hideDelayMs())
    }

    @Test
    fun aRangeOfOneValueAlwaysGivesThatValue() {
        val random = RecordingRandom()
        assertEquals(700L, EntryTiles(EntropyMode.Coins, random, HideDelay(700, 700)).hideDelayMs())
        assertEquals(listOf(1), random.bounds)
    }

    @Test
    fun everyHideDelayFromSecureRandomIsWithinTheSavedRangeAndReachesBothEnds() {
        val tiles = EntryTiles(EntropyMode.Dice, SecureRandom(), HideDelay(1_200, 1_500))
        val seen = mutableSetOf<Long>()

        repeat(10_000) {
            val delay = tiles.hideDelayMs()
            assertTrue(delay in 1_200L..1_500L, "delay $delay ms is out of range")
            seen += delay
        }
        assertTrue(1_200L in seen && 1_500L in seen, "the draw never reached an end of the range")
    }

    @Test
    fun everyHideDelayFromSecureRandomIsWithinTheDefaultRange() {
        val tiles = EntryTiles(EntropyMode.Dice, SecureRandom())

        repeat(10_000) {
            val delay = tiles.hideDelayMs()
            assertTrue(delay in HideDelay.DEFAULT_MIN_MS..HideDelay.DEFAULT_MAX_MS, "delay $delay ms is out of range")
        }
    }

    @Test
    fun theSixDiceAreAlwaysAPermutationOfTheSixFaces() {
        val tiles = EntryTiles(EntropyMode.Dice, SecureRandom())

        repeat(2_000) { step ->
            tiles.tap(step % tiles.faces.size)
            assertEquals("123456".toList(), tiles.faces.sorted())
            tiles.reveal()
        }
    }

    @Test
    fun theTwoCoinsAreAlwaysAPermutationOfHeadsAndTails() {
        val tiles = EntryTiles(EntropyMode.Coins, SecureRandom())

        repeat(2_000) { step ->
            tiles.tap(step % tiles.faces.size)
            assertEquals("HT".toList(), tiles.faces.sorted())
            tiles.reveal()
        }
    }
}
