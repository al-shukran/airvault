package app.lensly.ui.a11y

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.lensly.core.wallet.Mnemonic
import app.lensly.core.wallet.toHex
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.flow.RevealPart
import app.lensly.ui.flow.RevealStep
import app.lensly.ui.flow.RevealedSecret
import app.lensly.ui.gate.PasswordField
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LumenTheme
import app.lensly.ui.wallet.KeyBackupScreen
import app.lensly.ui.wallet.MnemonicDisplayScreen
import app.lensly.ui.wallet.RestoreEntryScreen
import app.lensly.ui.wallet.RestoreState
import app.lensly.ui.wallet.ShowSecretScreen
import app.lensly.ui.wallet.formatHexLines
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// BIP39 vector for 256 zero bits of entropy, and a passphrase, as in the screenshot fixtures.
private val artWords = List(23) { "abandon" } + "art"
private const val PASSPHRASE = "TREZOR"
private const val KEY_BYTES = 32

/** Matches a node that speaks one of [secrets] as its text or its description, compared without case. */
private fun exposesOneOf(secrets: List<String>): SemanticsMatcher {
    val lowered = secrets.map { it.lowercase() }.toSet()
    return SemanticsMatcher("exposes a secret text") { node ->
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        (texts + descriptions).any { it.lowercase() in lowered }
    }
}

/**
 * Semantics of the controls a screen reader must reach, checked on the JVM in the PR lane (guideline 1.9).
 *
 * These are not a full accessibility audit: they assert what the quick gate can: each control reports a label to
 * accessibility services, it is a click action, and where the control is the touch target it is at least 48 dp.
 * Everything else, contrast and the reading order in particular, is the manual TalkBack pass in
 * docs/testing/release-checklist.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class SemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyPasswordFieldAnnouncesThatItIsEmpty() {
        compose.setContent { LumenTheme { PasswordField(length = 0, modifier = Modifier.fillMaxWidth()) } }

        compose.onNodeWithContentDescription("Password empty").assertExists()
    }

    @Test
    fun filledPasswordFieldAnnouncesTheCharacterCount() {
        compose.setContent { LumenTheme { PasswordField(length = 14, modifier = Modifier.fillMaxWidth()) } }

        compose.onNodeWithContentDescription("14 characters entered").assertExists()
    }

    @Test
    fun passwordKeyboardKeysAreLabelledAndTallEnoughToTouch() {
        compose.setContent {
            LumenTheme {
                SecureKeyboard(onKey = {}, onBackspace = {}, onDone = {})
            }
        }

        for (key in listOf("a", "q", "1", "Shift", "Space", "Backspace", "Done", "Symbols")) {
            val node = compose.onNodeWithContentDescription(key).assertHasClickAction().fetchSemanticsNode()
            node.assertAtLeast48DpTall()
        }
    }

    @Test
    fun shiftAnnouncesItsOneShotState() {
        compose.setContent { LumenTheme { SecureKeyboard(onKey = {}, onBackspace = {}, onDone = {}) } }

        compose.onNodeWithContentDescription("Shift").performClick()

        compose.onNodeWithContentDescription("Shift").assert(hasStateDescription("Shift on"))
    }

    @Test
    fun theScreenFrameBackButtonIsLabelledAnd48Dp() {
        compose.setContent {
            LumenTheme {
                LenslyScreen(title = "Review", onBack = {}) {
                    Column {}
                }
            }
        }

        val node = compose.onNodeWithContentDescription("Back").assertHasClickAction().fetchSemanticsNode()
        node.assertAtLeast48DpTall()
        assertAtLeast48Dp(isWidth = true, node = node)
    }

    /**
     * The secret on Show Secret is for the owner's eyes. Each page speaks a position label instead, and the merged tree,
     * which is what accessibility services receive, carries no node with a secret text: not on any of the 24 words, the
     * passphrase, or the key.
     */
    @Test
    fun showSecretSpeaksNoSecretTextToAccessibility() {
        val mnemonic = RevealedSecret.ofMnemonic(Mnemonic.of(artWords), PASSPHRASE)
        val keyBytes = ByteArray(KEY_BYTES) { it.toByte() }
        val keyLines = formatHexLines(keyBytes.toHex())
        val key = RevealedSecret.ofKey(keyBytes)
        val exposesSecret = exposesOneOf(artWords + PASSPHRASE + keyLines)
        val pages = List(artWords.size) { RevealStep.Revealed(mnemonic, RevealPart.WORDS, it, 60) } +
            RevealStep.Revealed(mnemonic, RevealPart.PASSPHRASE, 0, 60) +
            RevealStep.Revealed(key, RevealPart.KEY, 0, 60)
        var step by mutableStateOf<RevealStep>(RevealStep.Warning)
        compose.setContent {
            LumenTheme {
                ShowSecretScreen(
                    step = step,
                    passwordLength = 0,
                    onKey = {},
                    onBackspace = {},
                    onSubmit = {},
                    onShow = {},
                    onMove = {},
                    onSelect = {},
                    onDone = {},
                )
            }
        }

        for (page in pages) {
            compose.runOnIdle { step = page }
            compose.waitForIdle()
            compose.onAllNodes(exposesSecret).assertCountEquals(0)
        }

        // The pages stay in reach of the reader, each under its position label.
        compose.runOnIdle { step = pages.first() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Word 1 of ${artWords.size}").assertExists()
        compose.runOnIdle { step = pages.last() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Single Key Hidden").assertExists()
    }

    @Test
    fun mnemonicDisplaySpeaksNoWordToAccessibility() {
        compose.setContent { LumenTheme { MnemonicDisplayScreen(words = artWords, onWrittenDown = {}) } }

        compose.onAllNodes(exposesOneOf(artWords)).assertCountEquals(0)
        compose.onNodeWithContentDescription("Word 1 of ${artWords.size}").assertExists()
    }

    @Test
    fun keyBackupSpeaksNoKeyLineToAccessibility() {
        val hex = ByteArray(KEY_BYTES) { it.toByte() }.toHex()
        compose.setContent {
            LumenTheme { KeyBackupScreen(hex = hex, copied = false, onCopiedChange = {}, onCreate = {}) }
        }

        compose.onAllNodes(exposesOneOf(formatHexLines(hex))).assertCountEquals(0)
        compose.onNodeWithContentDescription("Private Key Hidden").assertExists()
    }

    @Test
    fun restoreEntrySpeaksNoEnteredWordToAccessibility() {
        val state = RestoreState(words = artWords)
        compose.setContent {
            LumenTheme {
                RestoreEntryScreen(
                    state = state,
                    onKey = {},
                    onBackspace = {},
                    onPickSuggestion = {},
                    onEditWord = {},
                    onSubmit = {},
                )
            }
        }

        compose.onAllNodes(exposesOneOf(artWords)).assertCountEquals(0)
        compose.onNodeWithContentDescription("Word 1 of ${artWords.size}").assertExists()
    }

    /**
     * A key is 54 dp tall and stretches with the row. Ten keys on a phone are narrower than 48 dp, which is a layout
     * choice this test records rather than fixes: the width cannot grow without dropping keys from the row.
     */
    private fun SemanticsNode.assertAtLeast48DpTall() = assertAtLeast48Dp(isWidth = false, node = this)

    /**
     * The touch bounds, not the layout bounds: a Material component may draw at 40 dp and extend its touch target to
     * 48 dp, and it is the target that a finger has to hit.
     */
    private fun assertAtLeast48Dp(isWidth: Boolean, node: SemanticsNode) {
        val minimum = with(compose.density) { 48.dp.toPx() }
        val target = node.touchBoundsInRoot
        val actual = if (isWidth) target.width else target.height
        assertTrue(
            "the target is ${actual / compose.density.density} dp on the " +
                (if (isWidth) "width" else "height") + ", under 48 dp",
            actual >= minimum - 0.5f,
        )
    }
}
