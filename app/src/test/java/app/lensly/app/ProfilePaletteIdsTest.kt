package app.lensly.app

import kotlin.test.assertEquals
import org.junit.Test

/**
 * The palette and icon codes are stored as one byte each, and [ProfileColour.fromWire] takes the first match, so no two may share one.
 */
class ProfilePaletteIdsTest {

    @Test
    fun everyColourAndIconHasItsOwnCodeAndItsOwnName() {
        assertEquals(ProfileColour.entries.size, ProfileColour.entries.map { it.wire }.toSet().size)
        assertEquals(ProfileColour.entries.size, ProfileColour.entries.map { it.name }.toSet().size)
        assertEquals(ProfileIcon.entries.size, ProfileIcon.entries.map { it.wire }.toSet().size)
        assertEquals(ProfileIcon.entries.size, ProfileIcon.entries.map { it.name }.toSet().size)
    }
}
