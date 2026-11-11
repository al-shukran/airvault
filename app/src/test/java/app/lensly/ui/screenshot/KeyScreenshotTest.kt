package app.lensly.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.wallet.KeyAddressScreen
import app.lensly.ui.wallet.KeyBackupScreen
import app.lensly.ui.wallet.KeyEntryScreen
import app.lensly.ui.wallet.KeyEntryState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Private key import: typing the key, a refused key, the address check, and the write-down before the wallet is kept. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun keyEntryEmpty() = compose.snapshot("key_entry_empty") {
        KeyEntryScreen(state = KeyEntryState(), onKey = {}, onBackspace = {}, onDone = {})
    }

    @Test
    fun keyEntryTyping() = compose.snapshot("key_entry_typing") {
        KeyEntryScreen(state = KeyEntryState(length = 40), onKey = {}, onBackspace = {}, onDone = {})
    }

    @Test
    fun keyEntryRefused() = compose.snapshot("key_entry_refused") {
        KeyEntryScreen(
            state = KeyEntryState(length = 64, error = "Not a private key. Check the 64 hex digits."),
            onKey = {},
            onBackspace = {},
            onDone = {},
        )
    }

    @Test
    fun keyAddress() = compose.snapshot("key_address") {
        KeyAddressScreen(address = ADDRESS, onConfirm = {}, onBack = {})
    }

    @Test
    fun keyBackupNotCopied() = compose.snapshot("key_backup_not_copied") {
        KeyBackupScreen(hex = KEY, copied = false, onCopiedChange = {}, onCreate = {})
    }

    @Test
    fun keyBackupCopied() = compose.snapshot("key_backup_copied") {
        KeyBackupScreen(hex = KEY, copied = true, onCopiedChange = {}, onCreate = {})
    }
}

/** Sample values only. They are not keys or addresses anyone holds. */
private val KEY = "0123456789abcdef".repeat(4)
private const val ADDRESS = "0x0123456789abcdef0123456789abcdef01234567"
