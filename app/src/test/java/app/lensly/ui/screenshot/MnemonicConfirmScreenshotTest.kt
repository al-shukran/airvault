package app.lensly.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.wallet.ConfirmState
import app.lensly.ui.wallet.MnemonicConfirmScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The backup check: a question partway through, and the banner after a wrong word. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MnemonicConfirmScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val choices = listOf("harbor", "lizard", "orbit", "velvet")

    @Test
    fun question() = compose.snapshot("mnemonic_confirm_question") {
        MnemonicConfirmScreen(
            state = ConfirmState(step = 1, total = 4, position = 7, choices = choices),
            onPick = {},
            onBack = {},
        )
    }

    @Test
    fun wrongWord() = compose.snapshot("mnemonic_confirm_wrong_word") {
        MnemonicConfirmScreen(
            state = ConfirmState(step = 0, total = 4, position = 3, choices = choices, failed = true),
            onPick = {},
            onBack = {},
        )
    }
}
