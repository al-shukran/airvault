package app.lensly.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.lensly.ui.components.SecureHexKeyboard
import app.lensly.ui.components.SecureKeyboard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The in-app keyboard in each state it shows: letters, shift armed (the number row shows symbols), caps lock, the symbols page, and the
 * hex keyboard of a private key. Each sits at the foot of the frame, where a secret entry screen puts it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecureKeyboardScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun letters() = compose.snapshot("secure_keyboard_letters") {
        AtFoot { SecureKeyboard(onKey = {}, onBackspace = {}, onDone = {}) }
    }

    @Test
    fun shiftArmed() = compose.snapshot(
        "secure_keyboard_shift_armed",
        beforeCapture = { onNodeWithContentDescription("Shift").performClick() },
    ) {
        AtFoot { SecureKeyboard(onKey = {}, onBackspace = {}, onDone = {}) }
    }

    @Test
    fun capsLock() = compose.snapshot(
        "secure_keyboard_caps_lock",
        beforeCapture = {
            onNodeWithContentDescription("Shift").performClick()
            onNodeWithContentDescription("Shift").performClick()
        },
    ) {
        AtFoot { SecureKeyboard(onKey = {}, onBackspace = {}, onDone = {}) }
    }

    @Test
    fun symbols() = compose.snapshot(
        "secure_keyboard_symbols",
        beforeCapture = {
            onNodeWithContentDescription("Symbols").performClick()
            mainClock.advanceTimeBy(16)
        },
    ) {
        AtFoot { SecureKeyboard(onKey = {}, onBackspace = {}, onDone = {}) }
    }

    @Test
    fun moreSymbols() = compose.snapshot(
        "secure_keyboard_more_symbols",
        beforeCapture = {
            onNodeWithContentDescription("Symbols").performClick()
            mainClock.advanceTimeBy(16)
            onNodeWithContentDescription("More symbols").performClick()
            mainClock.advanceTimeBy(16)
        },
    ) {
        AtFoot { SecureKeyboard(onKey = {}, onBackspace = {}, onDone = {}) }
    }

    @Test
    fun alternatesOpen() = compose.snapshot(
        "secure_keyboard_alternates",
        beforeCapture = {
            // Held past the 400 ms long press, so the alternates of e are open at capture.
            onNodeWithContentDescription("e").performTouchInput { down(center) }
            mainClock.advanceTimeBy(500)
            waitForIdle()
        },
    ) {
        AtFoot { SecureKeyboard(onKey = {}, onBackspace = {}, onDone = {}) }
    }

    @Test
    fun hexDigits() = compose.snapshot("secure_keyboard_hex") {
        AtFoot { SecureHexKeyboard(onKey = {}, onBackspace = {}, onDone = {}) }
    }

    /** Puts the keyboard at the bottom of the frame. */
    @Composable
    private fun AtFoot(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            content()
        }
    }
}
