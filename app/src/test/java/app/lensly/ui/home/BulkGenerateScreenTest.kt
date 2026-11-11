package app.lensly.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.lensly.core.wallet.BulkCap
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bulk screen follows the Bulk Cap it is given. The count limit, the refusal, and the helper text all come from the
 * cap, and a cap changed in Settings is shown at once. The helper text is uppercase on screen, as the eyebrow shows it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class BulkGenerateScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** The inputs the screen reads. A test changes them, and the screen recomposes with the new values. */
    private class Inputs {
        var cap by mutableStateOf(BulkCap.HUNDRED)
        var digits by mutableStateOf("")
    }

    private val inputs = Inputs()

    private fun showScreen(cap: BulkCap, digits: String) {
        inputs.cap = cap
        inputs.digits = digits
        compose.setContent {
            LenslyTheme {
                BulkGenerateScreen(
                    existingAccounts = 3,
                    autoNameBase = "Seed wallet",
                    cap = inputs.cap,
                    digits = inputs.digits,
                    running = false,
                    derived = 0,
                    failure = null,
                    onDigit = {},
                    onBackspace = {},
                    onPreset = {},
                    onGenerate = {},
                    onBack = {},
                    onLock = {},
                )
            }
        }
    }

    private fun setCap(cap: BulkCap) = compose.runOnIdle { inputs.cap = cap }

    private fun setDigits(digits: String) = compose.runOnIdle { inputs.digits = digits }

    /** The text is on screen, once. */
    private fun assertShown(text: String) = compose.onAllNodesWithText(text).assertCountEquals(1)

    /** The text is not on screen. */
    private fun assertNotShown(text: String) = compose.onAllNodesWithText(text).assertCountEquals(0)

    @Test
    fun aHundredCapRefusesOneHundredAndOneAndAllowsOneHundred() {
        showScreen(BulkCap.HUNDRED, digits = "101")

        assertShown("Above the Bulk Cap of 100")
        compose.onNodeWithText("GENERATE").assertIsNotEnabled()

        setDigits("100")

        assertNotShown("Above the Bulk Cap of 100")
        compose.onNodeWithText("GENERATE 100").assertIsEnabled()
    }

    @Test
    fun aThousandCapAllowsOneThousandAndRefusesOneThousandAndOne() {
        showScreen(BulkCap.THOUSAND, digits = "1000")

        assertShown("ACCOUNTS, UP TO 1,000 PER RUN")
        compose.onNodeWithText("GENERATE 1000").assertIsEnabled()
        assertNotShown("Above the Bulk Cap of 1,000")

        setDigits("1001")

        assertShown("Above the Bulk Cap of 1,000")
        compose.onNodeWithText("GENERATE").assertIsNotEnabled()
    }

    @Test
    fun aChangedCapIsReflectedInTheScreen() {
        showScreen(BulkCap.HUNDRED, digits = "500")
        assertShown("ACCOUNTS, UP TO 100 PER RUN")
        assertShown("Above the Bulk Cap of 100")

        setCap(BulkCap.THOUSAND)

        assertShown("ACCOUNTS, UP TO 1,000 PER RUN")
        assertNotShown("Above the Bulk Cap of 100")
        compose.onNodeWithText("GENERATE 500").assertIsEnabled()

        setCap(BulkCap.HUNDRED)

        assertShown("ACCOUNTS, UP TO 100 PER RUN")
        assertShown("Above the Bulk Cap of 100")
        compose.onNodeWithText("GENERATE").assertIsNotEnabled()
    }

    @Test
    fun thePresetsAreShownUnderEveryCap() {
        showScreen(BulkCap.HUNDRED, digits = "")
        assertShown("10")
        assertShown("20")
        assertShown("50")

        setCap(BulkCap.THOUSAND)

        assertShown("10")
        assertShown("20")
        assertShown("50")
    }
}
