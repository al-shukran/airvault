package app.lensly.ui.wallet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import kotlin.test.assertTrue
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
        // The instance the screen was given. The wipe zeroes it, and the screen goes on holding it while it leaves.
        val held = draft.words
        var revision by mutableIntStateOf(0)

        compose.setContent {
            LenslyTheme {
                // The screen reads only the instance it holds, and keys a remember on it as the confirmation route does.
                remember(held) { held.size }
                // Each revision starts the screen again. Its composition then reads the wiped instance, which an equal
                // parameter would otherwise skip.
                key(revision) {
                    MnemonicDisplayScreen(words = held, onWrittenDown = {})
                }
            }
        }
        compose.onNodeWithText("24 WORDS, IN ORDER").assertExists()

        compose.runOnIdle {
            draft.wipe()
            revision++
        }
        compose.waitForIdle()

        compose.onNodeWithText("0 WORDS, IN ORDER").assertExists()
        assertTrue(draft.words.isEmpty(), "the draft itself no longer holds the words")

        // The instance the screen still holds is wiped, yet comparing, hashing and iterating it cannot throw.
        assertEquals(held, held)
        assertFalse(held == emptyList<String>())
        assertFalse(held == artWords)
        assertFalse(held.iterator().hasNext())
        assertEquals(System.identityHashCode(held), held.hashCode())
    }
}
