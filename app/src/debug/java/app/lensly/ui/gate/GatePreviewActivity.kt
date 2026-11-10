package app.lensly.ui.gate

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug-only harness that hosts the gate screens with local fake state, so they can be checked on a
 * device without the vault.
 *
 * Launch with:
 *   adb shell am start -n app.lensly/app.lensly.ui.gate.GatePreviewActivity --es screen create
 *   adb shell am start -n app.lensly/app.lensly.ui.gate.GatePreviewActivity --es screen unlock
 *
 * The policy here is a stand-in: fewer than [MIN_PASSWORD_LENGTH] characters is TooShort, and fewer
 * than four distinct characters is TooWeak. Unlock always fails after a simulated one-second
 * derivation, so the field clears silently.
 */
class GatePreviewActivity : ComponentActivity() {

    private val screen = mutableStateOf(SCREEN_CREATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen.value = screenFrom(intent)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.BLACK),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.BLACK),
        )

        setContent {
            val current by screen
            LenslyTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LenslyBlack),
                ) {
                    when (current) {
                        SCREEN_UNLOCK -> UnlockPreview()
                        else -> CreatePreview()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        screen.value = screenFrom(intent)
    }

    private fun screenFrom(intent: Intent): String =
        when (intent.getStringExtra(EXTRA_SCREEN)) {
            SCREEN_UNLOCK -> SCREEN_UNLOCK
            else -> SCREEN_CREATE
        }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_CREATE = "create"
        const val SCREEN_UNLOCK = "unlock"
    }
}

/** Fake policy for the preview. Returns null when the password passes. */
private fun previewPolicy(chars: CharArray): PolicyError? = when {
    chars.size < MIN_PASSWORD_LENGTH -> PolicyError.TooShort
    chars.toSet().size < 4 -> PolicyError.TooWeak
    else -> null
}

@Composable
private fun CreatePreview() {
    val context = LocalContext.current
    val buffer = remember { PasswordBuffer() }
    var first by remember { mutableStateOf<CharArray?>(null) }
    var state by remember { mutableStateOf(CreatePasswordState()) }

    CreatePasswordScreen(
        state = state,
        onKey = { c ->
            if (buffer.append(c)) state = state.copy(length = buffer.length, error = null)
        },
        onBackspace = {
            buffer.backspace()
            state = state.copy(length = buffer.length, error = null)
        },
        onSubmit = {
            when (state.step) {
                CreateStep.Enter -> {
                    val chars = buffer.toCharArray()
                    val error = previewPolicy(chars)
                    if (error == null) {
                        first?.fill('\u0000')
                        first = chars
                        buffer.clear()
                        state = CreatePasswordState(step = CreateStep.Confirm)
                    } else {
                        chars.fill('\u0000')
                        buffer.clear()
                        state = CreatePasswordState(step = CreateStep.Enter, error = error)
                    }
                }
                CreateStep.Confirm -> {
                    val chars = buffer.toCharArray()
                    val matches = first?.contentEquals(chars) == true
                    chars.fill('\u0000')
                    first?.fill('\u0000')
                    first = null
                    buffer.clear()
                    state = if (matches) {
                        Toast.makeText(context, "Preview: password accepted", Toast.LENGTH_SHORT).show()
                        CreatePasswordState()
                    } else {
                        CreatePasswordState(step = CreateStep.Confirm, error = PolicyError.Mismatch)
                    }
                }
            }
        },
    )
}

@Composable
private fun UnlockPreview() {
    val buffer = remember { PasswordBuffer() }
    var length by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    UnlockScreen(
        length = length,
        busy = busy,
        onKey = { c ->
            if (!busy && buffer.append(c)) length = buffer.length
        },
        onBackspace = {
            if (!busy) {
                buffer.backspace()
                length = buffer.length
            }
        },
        onSubmit = {
            if (!busy && buffer.length > 0) {
                busy = true
                scope.launch {
                    // Stands in for the roughly one-second key derivation.
                    delay(1000)
                    // Wrong-password path: clear silently, with no message.
                    buffer.clear()
                    length = 0
                    busy = false
                }
            }
        },
    )
}
