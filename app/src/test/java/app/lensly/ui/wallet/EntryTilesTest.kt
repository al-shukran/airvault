package app.lensly.ui.wallet

import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
    fun theHideDelayIsOneHundredPlusNextIntOf901() {
        val shortest = RecordingRandom()
        assertEquals(HIDE_MIN_MS, EntryTiles(EntropyMode.Dice, shortest).hideDelayMs())
        assertEquals(listOf(901), shortest.bounds)

        val longest = RecordingRandom { 900 }
        assertEquals(HIDE_MAX_MS, EntryTiles(EntropyMode.Dice, longest).hideDelayMs())
    }

    @Test
    fun everyHideDelayFromSecureRandomIsWithinOneHundredToOneThousandMs() {
        val tiles = EntryTiles(EntropyMode.Dice, SecureRandom())

        repeat(10_000) {
            val delay = tiles.hideDelayMs()
            assertTrue(delay in HIDE_MIN_MS..1_000L, "delay $delay ms is out of range")
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
