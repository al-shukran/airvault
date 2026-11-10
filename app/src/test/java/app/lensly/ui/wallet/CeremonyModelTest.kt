package app.lensly.ui.wallet

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class CeremonyModelTest {

    @Test
    fun diceAcceptsOnlyFacesOneToSix() {
        for (face in '1'..'6') assertTrue(EntropyMode.Dice.accepts(face))
        for (other in listOf('0', '7', 'H', 'T', ' ')) assertFalse(EntropyMode.Dice.accepts(other))
    }

    @Test
    fun coinsAcceptOnlyHeadsAndTails() {
        assertTrue(EntropyMode.Coins.accepts('H'))
        assertTrue(EntropyMode.Coins.accepts('T'))
        for (other in listOf('1', 'h', '6', ' ')) assertFalse(EntropyMode.Coins.accepts(other))
    }

    @Test
    fun defaultCountsMatchTheCeremony() {
        assertEquals(50, EntropyMode.Dice.defaultCount)
        assertEquals(128, EntropyMode.Coins.defaultCount)
        assertEquals(DICE_ROLL_COUNT, EntropyMode.Dice.defaultCount)
        assertEquals(COIN_FLIP_COUNT, EntropyMode.Coins.defaultCount)
    }

    @Test
    fun formatHexLinesGroupsByFourWithFourGroupsPerLine() {
        val hex = "0123456789abcdef".repeat(4)
        assertEquals(List(4) { "0123 4567 89AB CDEF" }, formatHexLines(hex))
    }

    @Test
    fun formatHexLinesKeepsAPartialLastGroup() {
        assertEquals(listOf("ABCD EF"), formatHexLines("abcdef"))
    }

    @Test
    fun formatHexLinesRejectsANonPositiveLineWidth() {
        assertFailsWith<IllegalArgumentException> { formatHexLines("ABCD", groupsPerLine = 0) }
    }

    @Test
    fun previewHexShowsTheFirstSixteenCharactersAndAnEllipsis() {
        val hex = "0123456789abcdef" + "00".repeat(24)
        assertEquals("0123 4567 89AB CDEF…", previewHex(hex))
    }
}
