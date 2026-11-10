package app.lensly.ui.components

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class OneShotShiftTest {

    @Test
    fun startsDisarmed() {
        assertFalse(OneShotShift().isArmed)
    }

    @Test
    fun tapArmsShift() {
        val shift = OneShotShift()
        shift.tapShift()
        assertTrue(shift.isArmed)
    }

    @Test
    fun nextLetterIsUppercaseAndSpendsShift() {
        val shift = OneShotShift()
        shift.tapShift()

        assertEquals('A', shift.type('a'))
        assertFalse(shift.isArmed)
        assertEquals('b', shift.type('b'))
    }

    @Test
    fun shiftOnlyCoversOneLetter() {
        val shift = OneShotShift()
        shift.tapShift()

        val typed = "hello".map { shift.type(it) }.joinToString("")

        assertEquals("Hello", typed)
        assertFalse(shift.isArmed)
    }

    @Test
    fun doubleTapLeavesShiftOffWithoutCapsLock() {
        val shift = OneShotShift()
        shift.tapShift()
        shift.tapShift()

        assertFalse(shift.isArmed)
        assertEquals("quick", "quick".map { shift.type(it) }.joinToString(""))
    }

    @Test
    fun tapWhileArmedTurnsShiftOffWithoutTypingAnything() {
        val shift = OneShotShift()
        shift.tapShift()
        shift.tapShift()

        assertEquals('a', shift.type('a'))
    }

    @Test
    fun digitsSymbolsSpaceAndPunctuationKeepShiftArmed() {
        val shift = OneShotShift()
        shift.tapShift()

        for (key in "1234567890@#\$%&*()-+!?_'\"/\\:; ,.") {
            assertEquals(key, shift.type(key))
            assertTrue(shift.isArmed, "'$key' must not spend shift")
        }

        assertEquals('A', shift.type('a'))
        assertFalse(shift.isArmed)
    }

    @Test
    fun labelShowsCaseWithoutSpendingShift() {
        val shift = OneShotShift()
        shift.tapShift()

        assertEquals('A', shift.labelFor('a'))
        assertEquals('A', shift.labelFor('a'))
        assertTrue(shift.isArmed)
    }

    @Test
    fun labelIsLowercaseWhileShiftIsOff() {
        assertEquals('q', OneShotShift().labelFor('q'))
    }

    @Test
    fun restoredArmedStateStillUppercasesOneLetter() {
        val shift = OneShotShift(armed = true)

        assertEquals('Z', shift.type('z'))
        assertEquals('z', shift.type('z'))
    }
}
