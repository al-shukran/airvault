package app.lensly.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.wallet.BulkCap
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LumenTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bulk screen follows the Bulk Cap it is given and the Count the owner types into the number field. The field refuses a Count above
 * the cap, or below 1, under the field, and GENERATE stays off for it. A cap changed in Settings is shown at once. The field takes digits
 * only, so what it shows is the Count the screen generates. Screen text is sentence case, so the lines are read as written.
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
        var chain by mutableStateOf<BulkChain>(BulkChain.Evm)
        var running by mutableStateOf(false)
    }

    private val inputs = Inputs()

    private fun showScreen(cap: BulkCap, digits: String, chain: BulkChain = BulkChain.Evm, running: Boolean = false) {
        inputs.cap = cap
        inputs.digits = digits
        inputs.chain = chain
        inputs.running = running
        compose.setContent {
            LumenTheme {
                BulkGenerateScreen(
                    existingAccounts = 3,
                    autoNameBase = "Seed wallet",
                    cap = inputs.cap,
                    digits = inputs.digits,
                    running = inputs.running,
                    derived = 0,
                    failure = null,
                    onDigits = { typed -> inputs.digits = typed },
                    onGenerate = {},
                    onBack = {},
                    chain = inputs.chain,
                    onChain = { chosen -> inputs.chain = chosen },
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
        compose.onNodeWithText("Generate").assertIsNotEnabled()

        setDigits("100")

        assertNotShown("Above the Bulk Cap of 100")
        compose.onNodeWithText("Generate 100 Accounts").assertIsEnabled()
    }

    @Test
    fun aTwentyCapAllowsTwentyAndRefusesTwentyOne() {
        showScreen(BulkCap.TWENTY, digits = "21")

        assertShown("Above the Bulk Cap of 20")
        compose.onNodeWithText("Generate").assertIsNotEnabled()

        setDigits("20")

        assertNotShown("Above the Bulk Cap of 20")
        compose.onNodeWithText("Generate 20 Accounts").assertIsEnabled()
    }

    @Test
    fun aChangedCapIsReflectedInTheScreen() {
        showScreen(BulkCap.HUNDRED, digits = "30")
        assertShown("Up to 100 per run. Change the Bulk Cap in Settings.")
        compose.onNodeWithText("Generate 30 Accounts").assertIsEnabled()

        setCap(BulkCap.TWENTY)

        assertShown("Up to 20 per run. Change the Bulk Cap in Settings.")
        assertShown("Above the Bulk Cap of 20")
        compose.onNodeWithText("Generate").assertIsNotEnabled()

        setCap(BulkCap.HUNDRED)

        assertShown("Up to 100 per run. Change the Bulk Cap in Settings.")
        assertNotShown("Above the Bulk Cap of 20")
        compose.onNodeWithText("Generate 30 Accounts").assertIsEnabled()
    }

    @Test
    fun aTypedCountSetsTheRunThroughTheNumberField() {
        showScreen(BulkCap.HUNDRED, digits = "")

        compose.onNode(hasSetTextAction()).performTextReplacement("25")

        compose.onNodeWithText("Generate 25 Accounts").assertIsEnabled()
        assertShown("Adds Seed wallet 3 to Seed wallet 27")
    }

    @Test
    fun anEmptyCountFieldIsNotAnErrorButCannotGenerate() {
        showScreen(BulkCap.HUNDRED, digits = "")

        assertNotShown("At least 1 account")
        assertNotShown("Above the Bulk Cap of 100")
        compose.onNodeWithText("Generate").assertIsNotEnabled()
    }

    @Test
    fun zeroIsRefusedUnderTheField() {
        showScreen(BulkCap.HUNDRED, digits = "")

        compose.onNode(hasSetTextAction()).performTextReplacement("0")

        assertShown("At least 1 account")
        compose.onNodeWithText("Generate").assertIsNotEnabled()
    }

    @Test
    fun textThatIsNotDigitsOrIsTooLongIsDroppedFromTheField() {
        showScreen(BulkCap.HUNDRED, digits = "")

        // Letters and a tenth digit never reach the screen, so the Count stays empty and no error shows.
        compose.onNode(hasSetTextAction()).performTextReplacement("1a")
        compose.onNodeWithText("Generate").assertIsNotEnabled()
        assertNotShown("At least 1 account")

        compose.onNode(hasSetTextAction()).performTextReplacement("1234567890")
        compose.onNodeWithText("Generate").assertIsNotEnabled()
        assertNotShown("Above the Bulk Cap of 100")

        compose.onNode(hasSetTextAction()).performTextReplacement("5")
        compose.onNodeWithText("Generate 5 Accounts").assertIsEnabled()
    }

    @Test
    fun theAddsLineIsInSentenceCase() {
        showScreen(BulkCap.HUNDRED, digits = "20")

        assertShown("Adds Seed wallet 3 to Seed wallet 22")
    }

    @Test
    fun theScreenHasNoKeypadOrPresetPills() {
        showScreen(BulkCap.HUNDRED, digits = "")

        for (key in listOf("1", "5", "9")) assertNotShown(key)
        assertNotShown("10")
        assertNotShown("20")
        assertNotShown("100")
        assertShown("Ethereum · All Networks")
    }

    @Test
    fun anEthereumRunHasNoScriptTypeRow() {
        showScreen(BulkCap.HUNDRED, digits = "2")

        assertNotShown("Script Type · Native Segwit")
    }

    @Test
    fun aBitcoinRunStartsOnNativeSegwitAndNamesItInTheRowAndTheAddsLine() {
        showScreen(BulkCap.HUNDRED, digits = "3", chain = DEFAULT_BITCOIN)

        assertShown("Bitcoin")
        assertShown("Script Type · Native Segwit")
        assertShown("Adds 3 Native Segwit accounts")
    }

    @Test
    fun pickingAScriptTypeFromTheListSetsTheRun() {
        showScreen(BulkCap.HUNDRED, digits = "3", chain = DEFAULT_BITCOIN)

        compose.onNodeWithText("Script Type · Native Segwit").performClick()
        compose.onNodeWithText("Taproot").performClick()

        assertEquals(BulkChain.Bitcoin(ScriptType.TAPROOT, BtcNetwork.MAINNET), inputs.chain)
        assertShown("Script Type · Taproot")
        assertShown("Adds 3 Taproot accounts")
        compose.onNodeWithText("Generate 3 Accounts").assertIsEnabled()
    }

    @Test
    fun theListOffersTheFourScriptTypes() {
        showScreen(BulkCap.HUNDRED, digits = "3", chain = DEFAULT_BITCOIN)

        compose.onNodeWithText("Script Type · Native Segwit").performClick()

        assertShown("Script Type")
        assertShown("Native Segwit")
        assertShown("Taproot")
        assertShown("Nested Segwit")
        assertShown("Legacy")
    }

    @Test
    fun theScriptTypeCannotBeChangedWhileARunDerives() {
        showScreen(BulkCap.HUNDRED, digits = "3", chain = DEFAULT_BITCOIN, running = true)

        compose.onNodeWithText("Script Type · Native Segwit").assertIsNotEnabled()
        assertNotShown("Script Type")
    }
}
