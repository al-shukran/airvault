package app.lensly.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LumenTheme
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * The address field. Paste reads the clipboard's text into the field, trimmed, and the validator decides whether the value is an
 * address. A refused value shows the validator's words under the field, and an empty clipboard keeps what the field had.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class AddressFieldTest {

    @get:Rule
    val compose = createComposeRule()

    /** The field's value, as the screen under test last saw it. */
    private var current = ""

    @Test
    fun pasteFillsTheFieldWithTheTrimmedClipboardText() {
        putOnClipboard("  $VALID  ")
        setField(initial = "")

        compose.onNodeWithText(PASTE).performClick()

        assertEquals(VALID, current)
    }

    @Test
    fun aPastedAddressTheValidatorAcceptsShowsNoError() {
        putOnClipboard(VALID)
        setField(initial = "")

        compose.onNodeWithText(PASTE).performClick()

        compose.onNodeWithText(REFUSED).assertDoesNotExist()
    }

    @Test
    fun aPastedValueTheValidatorRefusesShowsItsWordsUnderTheField() {
        putOnClipboard("not an address")
        setField(initial = "")

        compose.onNodeWithText(PASTE).performClick()

        assertEquals("not an address", current)
        compose.onNodeWithText(REFUSED).assertExists()
    }

    @Test
    fun anEmptyClipboardKeepsTheFieldAndSaysSo() {
        clipboard().clearPrimaryClip()
        setField(initial = "kept")

        compose.onNodeWithText(PASTE).performClick()

        assertEquals("kept", current)
        assertEquals(CLIPBOARD_EMPTY, ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun noErrorIsShownWhileTheFieldIsEmpty() {
        setField(initial = "")

        compose.onNodeWithText(REFUSED).assertDoesNotExist()
    }

    @Test
    fun aTypedValueTheValidatorRefusesShowsItsWords() {
        setField(initial = "0x12")

        compose.onNodeWithText(REFUSED).assertExists()
    }

    @Test
    fun clipboardTextIsTheTrimmedTextOrNullWhenThereIsNone() {
        val context = RuntimeEnvironment.getApplication()
        putOnClipboard("  $VALID \n")
        assertEquals(VALID, clipboardText(context))

        clipboard().clearPrimaryClip()
        assertNull(clipboardText(context))
    }

    private fun setField(initial: String) {
        current = initial
        compose.setContent {
            var text by remember { mutableStateOf(initial) }
            LumenTheme {
                AddressField(
                    value = text,
                    onValueChange = {
                        text = it
                        current = it
                    },
                    label = "Recipient",
                    validate = { value -> if (value == VALID) null else REFUSED },
                )
            }
        }
    }

    private fun clipboard(): ClipboardManager =
        RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)

    private fun putOnClipboard(text: String) {
        clipboard().setPrimaryClip(ClipData.newPlainText("test", text))
    }

    private companion object {
        const val VALID = "0x52908400098527886E0F7030069857D2E4169EE7"
        const val PASTE = "Paste"
        const val REFUSED = "Not an address of this network."
    }
}
