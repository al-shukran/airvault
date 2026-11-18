package app.lensly.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue

/** The states of the shift key in [SecureKeyboard]. */
internal enum class ShiftMode { Off, OneShot, CapsLock }

/** Two taps on shift this close together, in milliseconds, lock caps. */
internal const val DOUBLE_TAP_MS = 400L

/** The digits of the number row, and the symbol on each digit's key with shift on, in the same order. */
internal const val DIGIT_KEYS = "1234567890"
internal const val SHIFTED_DIGIT_KEYS = "!@#\$%^&*()"

/**
 * Shift state for [SecureKeyboard], kept free of composables so the rules can be unit tested.
 *
 * A tap on shift arms it for one key. The next letter or digit typed spends it: a letter comes out uppercase, and a digit comes out as
 * the symbol on its key (1 gives !). Caps lock uppercases letters and leaves the digits alone, as a hardware keyboard does. Space, the
 * comma, the period, and the other symbols do not spend a one-shot shift, as in AOSP LatinIME. A second tap within [DOUBLE_TAP_MS] of
 * the first locks caps until shift is tapped again. Any other tap turns shift off without typing anything.
 *
 * [reset] clears shift. The keyboard calls it when it leaves the letters, so an armed shift never reaches the symbols page.
 *
 * [now] reads the clock in milliseconds. Tests pass their own, so a double tap needs no real waiting.
 */
internal class Shift(
    mode: ShiftMode = ShiftMode.Off,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    var mode: ShiftMode by mutableStateOf(mode)
        private set

    /** When shift was last tapped. A second tap counts as a double tap only if it follows the arming tap in time. */
    private var lastTapAt: Long? = null

    /**
     * The label for [key] now: a letter is uppercase while shift is on, and a digit shows its symbol while a one-shot shift is armed.
     * Does not spend shift.
     */
    fun labelFor(key: Char): Char = when {
        key.isLetter() -> if (mode == ShiftMode.Off) key else key.uppercaseChar()
        mode == ShiftMode.OneShot && key in DIGIT_KEYS -> SHIFTED_DIGIT_KEYS[DIGIT_KEYS.indexOf(key)]
        else -> key
    }

    /** A tap on the shift key. */
    fun tapShift() {
        val at = now()
        val doubleTap = lastTapAt?.let { at - it <= DOUBLE_TAP_MS } == true
        mode = when (mode) {
            ShiftMode.Off -> ShiftMode.OneShot
            ShiftMode.OneShot -> if (doubleTap) ShiftMode.CapsLock else ShiftMode.Off
            ShiftMode.CapsLock -> ShiftMode.Off
        }
        lastTapAt = at
    }

    /**
     * Returns the character to type for a key tap. A letter or digit takes the case or symbol of shift, and a one-shot shift is spent on
     * it. Any other key is returned as it is and shift is not changed.
     */
    fun type(key: Char): Char {
        val typed = labelFor(key)
        if (mode == ShiftMode.OneShot && (key.isLetter() || key in DIGIT_KEYS)) mode = ShiftMode.Off
        return typed
    }

    /** Clears shift and caps lock, as leaving the letters does. */
    fun reset() {
        mode = ShiftMode.Off
        lastTapAt = null
    }
}

/**
 * Keeps the shift mode across recomposition and activity recreation. The tap time is not kept, so a restored one-shot turns off on
 * its next tap instead of locking caps.
 */
internal val ShiftSaver: Saver<Shift, String> = Saver(
    save = { it.mode.name },
    restore = { Shift(mode = ShiftMode.valueOf(it)) },
)
