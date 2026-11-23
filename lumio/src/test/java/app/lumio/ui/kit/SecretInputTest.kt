package app.lumio.ui.kit

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.isEditable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.ui.settings.ApiKeyField
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every secret field gets the system keyboard's no-suggestion and no-learning flags, and an ordinary field keeps the defaults. Each field is
 * focused, then the Compose view it sits in builds the input connection, and the [EditorInfo] it fills is read.
 */
@RunWith(RobolectricTestRunner::class)
class SecretInputTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun passwordFieldTakesTheSecretFlags() {
        compose.setContent {
            ClearAirTheme { ClearAirField(label = "Password", value = "", onValueChange = {}, keyboard = KeyboardType.Password, masked = true) }
        }
        assertSecretFlags()
    }

    @Test
    fun apiKeyFieldTakesTheSecretFlags() {
        compose.setContent { ClearAirTheme { ApiKeyField(id = "provider", stored = null, onSave = {}) } }
        assertSecretFlags()
    }

    @Test
    fun ordinaryFieldKeepsTheKeyboardDefaults() {
        compose.setContent {
            ClearAirTheme { ClearAirField(label = "Address", value = "", onValueChange = {}) }
        }
        compose.onNode(isEditable()).performClick()
        val info = editorInfoOfFocusedField()
        assertFalse(info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertFalse(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }

    private fun assertSecretFlags() {
        compose.onNode(isEditable()).performClick()
        val info = editorInfoOfFocusedField()
        assertTrue("the secret field suggests words", info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertTrue("the secret field learns words", info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }

    private fun editorInfoOfFocusedField(): EditorInfo {
        val info = EditorInfo()
        val connection = composeView().onCreateInputConnection(info)
        assertNotNull("the focused field opened no input connection", connection)
        return info
    }

    private fun composeView(): View =
        requireNotNull(compose.activity.window.decorView.findComposeView()) { "no Compose view in the window" }

    private fun View.findComposeView(): View? = when {
        javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" -> this
        this is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findComposeView() }
        else -> null
    }
}
