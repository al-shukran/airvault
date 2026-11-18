package app.lensly.ui.components

import kotlin.test.assertEquals
import org.junit.Test

/** The clock is a variable the test moves, so a double tap is decided without waiting. */
class ShiftTest {

    private var clock = 0L
    private val shift = Shift(now = { clock })

    private fun tapAt(ms: Long) {
        clock = ms
        shift.tapShift()
    }

    private fun typeAll(text: String): String = text.map { shift.type(it) }.joinToString("")

    @Test
    fun startsOff() {
        assertEquals(ShiftMode.Off, shift.mode)
    }

    @Test
    fun aSingleTapArmsOneShot() {
        tapAt(0)

        assertEquals(ShiftMode.OneShot, shift.mode)
    }

    @Test
    fun theNextLetterIsUppercaseAndSpendsOneShot() {
        tapAt(0)

        assertEquals('A', shift.type('a'))
        assertEquals(ShiftMode.Off, shift.mode)
        assertEquals('b', shift.type('b'))
    }

    @Test
    fun theOneShotCoversOnlyOneLetter() {
        tapAt(0)

        assertEquals("Hello", typeAll("hello"))
        assertEquals(ShiftMode.Off, shift.mode)
    }

    @Test
    fun aDoubleTapLocksCaps() {
        tapAt(0)
        tapAt(300)

        assertEquals(ShiftMode.CapsLock, shift.mode)
        assertEquals("QUICK", typeAll("quick"))
        assertEquals(ShiftMode.CapsLock, shift.mode)
    }

    @Test
    fun aTapOnCapsLockUnlocks() {
        tapAt(0)
        tapAt(300)

        tapAt(1_000)

        assertEquals(ShiftMode.Off, shift.mode)
        assertEquals("quick", typeAll("quick"))
    }

    @Test
    fun aSecondTapAfterTheWindowIsNotALock() {
        tapAt(0)

        tapAt(1_000)

        assertEquals(ShiftMode.Off, shift.mode)
        assertEquals('a', shift.type('a'))
    }

    @Test
    fun aSecondTapExactlyAtTheWindowLocksCaps() {
        tapAt(0)
        tapAt(DOUBLE_TAP_MS)

        assertEquals(ShiftMode.CapsLock, shift.mode)
    }

    @Test
    fun aSecondTapJustAfterTheWindowIsNotALock() {
        tapAt(0)
        tapAt(DOUBLE_TAP_MS + 1)

        assertEquals(ShiftMode.Off, shift.mode)
    }

    @Test
    fun aLetterBetweenTapsMakesTheNextTapAOneShotAgain() {
        tapAt(0)
        shift.type('a')

        tapAt(150)

        assertEquals(ShiftMode.OneShot, shift.mode)
        assertEquals('B', shift.type('b'))
    }

    @Test
    fun digitsSymbolsSpaceAndPunctuationKeepOneShot() {
        tapAt(0)

        for (key in "1234567890@#\$%&*()-+!?_'\"/\\:; ,.[]{}<>=~^|") {
            assertEquals(key, shift.type(key))
            assertEquals(ShiftMode.OneShot, shift.mode, "'$key' must not spend shift")
        }

        assertEquals('A', shift.type('a'))
    }

    @Test
    fun digitsKeepCapsLockOn() {
        tapAt(0)
        tapAt(100)

        assertEquals("1A", "1a".map { shift.type(it) }.joinToString(""))
        assertEquals(ShiftMode.CapsLock, shift.mode)
    }

    @Test
    fun labelShowsCaseWithoutSpendingOneShot() {
        tapAt(0)

        assertEquals('A', shift.labelFor('a'))
        assertEquals('A', shift.labelFor('a'))
        assertEquals(ShiftMode.OneShot, shift.mode)
    }

    @Test
    fun labelIsUppercaseWhileCapsLockIsOn() {
        tapAt(0)
        tapAt(100)

        assertEquals('Q', shift.labelFor('q'))
        assertEquals(ShiftMode.CapsLock, shift.mode)
    }

    @Test
    fun labelIsLowercaseWhileShiftIsOff() {
        assertEquals('q', Shift().labelFor('q'))
    }

    @Test
    fun aRestoredOneShotUppercasesOneLetter() {
        val restored = Shift(mode = ShiftMode.OneShot)

        assertEquals('Z', restored.type('z'))
        assertEquals('z', restored.type('z'))
    }

    @Test
    fun aRestoredCapsLockStaysOn() {
        val restored = Shift(mode = ShiftMode.CapsLock)

        assertEquals('Z', restored.type('z'))
        assertEquals('Z', restored.type('z'))
        assertEquals(ShiftMode.CapsLock, restored.mode)
    }

    @Test
    fun aRestoredOneShotTurnsOffOnItsNextTapInsteadOfLocking() {
        val restored = Shift(mode = ShiftMode.OneShot, now = { clock })

        clock = 0
        restored.tapShift()

        assertEquals(ShiftMode.Off, restored.mode)
    }
}
