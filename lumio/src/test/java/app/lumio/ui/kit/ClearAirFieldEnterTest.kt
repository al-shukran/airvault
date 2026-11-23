package app.lumio.ui.kit

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isEditable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.ui.theme.ClearAirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * On a secret field, the Enter key never types text. An IME Next or Done, and a hardware Enter, move focus to the next field, and the last field
 * submits instead.
 */
@RunWith(RobolectricTestRunner::class)
class ClearAirFieldEnterTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var password by mutableStateOf("secret")
    private var confirm by mutableStateOf("")
    private var submitted = 0

    private fun showPasswordAndConfirm() {
        compose.setContent {
            ClearAirTheme {
                Column {
                    ClearAirField(
                        label = "Password",
                        value = password,
                        onValueChange = { password = it },
                        keyboard = KeyboardType.Password,
                        masked = true,
                    )
                    ClearAirField(
                        label = "Repeat Password",
                        value = confirm,
                        onValueChange = { confirm = it },
                        keyboard = KeyboardType.Password,
                        masked = true,
                        onSubmit = { submitted++ },
                    )
                }
            }
        }
    }

    private fun assertRepeatFocused() = assertTrue(
        compose.onAllNodes(isEditable())[1].fetchSemanticsNode().config[SemanticsProperties.Focused],
    )

    @Test
    fun imeNextOnPasswordMovesToRepeatWithoutTypingAnything() {
        showPasswordAndConfirm()
        compose.onAllNodes(isEditable())[0].performClick()
        compose.onAllNodes(isEditable())[0].performImeAction()
        assertRepeatFocused()
        assertEquals("secret", password)
        assertEquals("", confirm)
        assertEquals(0, submitted)
    }

    @Test
    fun imeDoneOnRepeatSubmitsWithoutTypingAnything() {
        showPasswordAndConfirm()
        compose.onAllNodes(isEditable())[1].performClick()
        compose.onAllNodes(isEditable())[1].performImeAction()
        assertEquals(1, submitted)
        assertEquals("", confirm)
    }

    @Test
    fun hardwareEnterMovesOnThenSubmitsWithoutTypingAnything() {
        showPasswordAndConfirm()
        compose.onAllNodes(isEditable())[0].performClick()
        compose.onAllNodes(isEditable())[0].performKeyInput { pressKey(Key.Enter) }
        assertRepeatFocused()
        assertEquals("secret", password)
        assertEquals(0, submitted)

        compose.onAllNodes(isEditable())[1].performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, submitted)
        assertEquals("", confirm)
    }
}
