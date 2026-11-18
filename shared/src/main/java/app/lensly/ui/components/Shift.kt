package app.lensly.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue

/** The states of the shift key in [SecureKeyboard]. */
internal enum class ShiftMode { Off, OneShot, CapsLock }

/** Two taps on shift this close together, in milliseconds, lock caps. */
internal const val DOUBLE_TAP_MS = 400L

/**
 * Shift state for [SecureKeyboard], kept free of composables so the rules can be unit tested.
 *
 * A tap on shift arms it for one letter, and the next letter typed turns it off. A second tap within [DOUBLE_TAP_MS] of the first
 * locks caps, so every letter stays uppercase until shift is tapped again. A tap at any other time turns shift off without typing
 * anything.
 *
 * Only a letter spends a one-shot shift. Digits, symbols, space, comma, and period are returned unchanged and leave shift as it is.
 * Backspace and the page keys never reach [type], so they leave it alone too.
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

    /** The label for [letter] now: uppercase while shift is on. Does not spend shift. */
    fun labelFor(letter: Char): Char = if (mode == ShiftMode.Off) letter else letter.uppercaseChar()

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
     * Returns the character to type for a key tap. A letter takes the case of shift, and a one-shot shift is spent. Any other
     * character is returned as it is and shift is not changed.
     */
    fun type(key: Char): Char {
        if (!key.isLetter()) return key
        val typed = labelFor(key)
        if (mode == ShiftMode.OneShot) mode = ShiftMode.Off
        return typed
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
