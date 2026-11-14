package app.lensly.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue

/**
 * Shift state for [SecureKeyboard], kept free of composables so the rules can be unit tested.
 *
 * Shift is one-shot. A tap on shift arms it, the next letter typed is uppercase, and that letter
 * turns shift off. A tap on shift while it is armed turns it off without typing anything. There is
 * no caps lock: no gesture keeps shift on past one letter, so a double tap leaves it off.
 *
 * Only a letter spends shift. Digits, symbols, space, comma, and period are returned unchanged and
 * leave shift as it is. Backspace and the page keys never reach [type], so they leave it alone too.
 */
internal class OneShotShift(armed: Boolean = false) {
    var isArmed: Boolean by mutableStateOf(armed)
        private set

    /** The label for [letter] now: uppercase while shift is armed. Does not spend shift. */
    fun labelFor(letter: Char): Char = if (isArmed) letter.uppercaseChar() else letter

    /** A tap on the shift key. Arms shift, or disarms it when it is already armed. */
    fun tapShift() {
        isArmed = !isArmed
    }

    /**
     * Returns the character to type for a key tap. A letter takes the case of shift and spends it.
     * Any other character is returned as it is and shift is not changed.
     */
    fun type(key: Char): Char {
        if (!key.isLetter()) return key
        val typed = labelFor(key)
        isArmed = false
        return typed
    }
}

/** Keeps the armed flag across recomposition and activity recreation, as the old toggle did. */
internal val OneShotShiftSaver: Saver<OneShotShift, Boolean> = Saver(
    save = { it.isArmed },
    restore = { OneShotShift(armed = it) },
)
