package app.lensly.ui.wallet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.lensly.app.WalletDraft
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A screen may still hold the draft's words when the draft is wiped. The recomposition after that must not throw, and
 * the instance the screen held must still compare, hash and iterate without reading its zeroed buffers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class MnemonicWipeRecompositionTest {

    @get:Rule
    val compose = createComposeRule()

    // BIP39 vector for 256 zero bits of entropy.
    private val artWords = List(23) { "abandon" } + "art"

    @Test
    fun aWipeWhileTheScreenShowsDoesNotThrowOnRecomposition() {
        val draft = WalletDraft()
        draft.setTypedWords(artWords)
        val shown = draft.words
        var revision by mutableIntStateOf(0)

        compose.setContent {
            LenslyTheme {
                // Read the words the way the routes do, and key a remember on them as the confirmation route does.
                val words = draft.words
                remember(words) { words.size }
                revision

                MnemonicDisplayScreen(words = words, onWrittenDown = {})
            }
        }
        compose.onNodeWithText("24 WORDS, IN ORDER").assertExists()

        compose.runOnIdle {
            draft.wipe()
            revision++
        }
        compose.waitForIdle()

        compose.onNodeWithText("0 WORDS, IN ORDER").assertExists()

        // The instance the screen still holds is wiped, yet comparing, hashing and iterating it cannot throw.
        assertEquals(shown, shown)
        assertFalse(shown == emptyList<String>())
        assertFalse(shown == artWords)
        assertFalse(shown.iterator().hasNext())
        assertEquals(System.identityHashCode(shown), shown.hashCode())
    }
}
