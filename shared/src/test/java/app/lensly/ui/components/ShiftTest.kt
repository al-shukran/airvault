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
    fun theNumberRowTypesItsSymbolAndSpendsOneShot() {
        tapAt(0)

        assertEquals('!', shift.type('1'))
        assertEquals(ShiftMode.Off, shift.mode)
        assertEquals('2', shift.type('2'))
    }

    @Test
    fun everyDigitTypesTheSymbolAboveItUnderOneShot() {
        val typed = "1234567890".map { digit -> Shift(mode = ShiftMode.OneShot).type(digit) }.joinToString("")

        assertEquals("!@#\$%^&*()", typed)
    }

    @Test
    fun spaceCommaPeriodAndSymbolsKeepOneShot() {
        tapAt(0)

        for (key in " ,.-+[]{}<>=~^|!?_'\"/\\:;@#\$%&*()") {
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
    fun capsLockLeavesTheNumberRowAsDigits() {
        tapAt(0)
        tapAt(100)

        assertEquals("1234567890", "1234567890".map { shift.labelFor(it) }.joinToString(""))
    }

    @Test
    fun labelShowsCaseAndSymbolWithoutSpendingOneShot() {
        tapAt(0)

        assertEquals('A', shift.labelFor('a'))
        assertEquals('A', shift.labelFor('a'))
        assertEquals('!', shift.labelFor('1'))
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
    fun labelIsLowercaseAndDigitsWhileShiftIsOff() {
        assertEquals('q', Shift().labelFor('q'))
        assertEquals('1', Shift().labelFor('1'))
    }

    @Test
    fun resetClearsOneShotAndCapsLock() {
        tapAt(0)
        tapAt(100)

        shift.reset()

        assertEquals(ShiftMode.Off, shift.mode)
        assertEquals('a', shift.type('a'))
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
