package app.lumio.ui.kit

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession

/**
 * Marks the text fields inside [content] as secret. The system keyboard then gets the flags that keep the typed text out of its learned words and
 * suggestions: no suggestions and no personalized learning. Compose offers neither flag, so the request for the input connection is wrapped here.
 * Autofill needs no flag: a Compose text field offers itself to autofill only through Modifier.contentType, and the secret fields set none.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SecretInput(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(interceptor = secretInterceptor, content = content)
}

@OptIn(ExperimentalComposeUiApi::class)
internal val secretInterceptor = object : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        session: PlatformTextInputSession,
    ): Nothing {
        session.startInputMethod(object : PlatformTextInputMethodRequest {
            override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                val connection = request.createInputConnection(outAttributes)
                outAttributes.inputType = outAttributes.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                return connection
            }
        })
    }
}
