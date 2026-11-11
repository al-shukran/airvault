package app.lensly.app

import kotlin.test.assertEquals
import org.junit.Test

/**
 * The stored codes of the palette and the icon set. A record written by an older build holds a code, so a code must keep its
 * meaning for ever, and the list may only grow at the end.
 */
class ProfilePaletteIdsTest {

    /** The codes the first build wrote, and the colour each one meant. */
    private val legacyColours = mapOf(
        1 to ProfileColour.RED,
        2 to ProfileColour.ORANGE,
        3 to ProfileColour.YELLOW,
        4 to ProfileColour.GREEN,
        5 to ProfileColour.TEAL,
        6 to ProfileColour.BLUE,
        7 to ProfileColour.VIOLET,
        8 to ProfileColour.PINK,
    )

    /** The codes the first build wrote, and the icon each one meant. */
    private val legacyIcons = mapOf(
        1 to ProfileIcon.STAR,
        2 to ProfileIcon.HEART,
        3 to ProfileIcon.HOME,
        4 to ProfileIcon.PERSON,
        5 to ProfileIcon.PIN,
        6 to ProfileIcon.WRENCH,
        7 to ProfileIcon.SEND,
        8 to ProfileIcon.THUMB_UP,
        9 to ProfileIcon.BELL,
        10 to ProfileIcon.CART,
        11 to ProfileIcon.CALENDAR,
        12 to ProfileIcon.PHONE,
    )

    @Test
    fun everyColourCodeOfTheFirstBuildStillReadsAsTheSameColour() {
        legacyColours.forEach { (wire, colour) ->
            assertEquals(colour, ProfileColour.fromWire(wire))
            assertEquals(wire, colour.wire)
        }
    }

    @Test
    fun everyIconCodeOfTheFirstBuildStillReadsAsTheSameIcon() {
        legacyIcons.forEach { (wire, icon) ->
            assertEquals(icon, ProfileIcon.fromWire(wire))
            assertEquals(wire, icon.wire)
        }
    }

    @Test
    fun theColoursKeepTheirOrderAndTheNewOnesComeAfterTheOldOnes() {
        assertEquals(legacyColours.values.toList(), ProfileColour.entries.take(legacyColours.size))
        assertEquals(
            (legacyColours.size + 1..ProfileColour.entries.size).toSet(),
            ProfileColour.entries.drop(legacyColours.size).map { it.wire }.toSet(),
        )
    }

    @Test
    fun theIconsKeepTheirOrderAndTheNewOnesComeAfterTheOldOnes() {
        assertEquals(legacyIcons.values.toList(), ProfileIcon.entries.take(legacyIcons.size))
        assertEquals(
            (legacyIcons.size + 1..ProfileIcon.entries.size).toSet(),
            ProfileIcon.entries.drop(legacyIcons.size).map { it.wire }.toSet(),
        )
    }

    @Test
    fun everyColourAndIconHasItsOwnCodeAndItsOwnName() {
        assertEquals(ProfileColour.entries.size, ProfileColour.entries.map { it.wire }.toSet().size)
        assertEquals(ProfileColour.entries.size, ProfileColour.entries.map { it.name }.toSet().size)
        assertEquals(ProfileIcon.entries.size, ProfileIcon.entries.map { it.wire }.toSet().size)
        assertEquals(ProfileIcon.entries.size, ProfileIcon.entries.map { it.name }.toSet().size)
    }
}
