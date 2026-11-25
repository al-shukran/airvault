package app.lensly.ui.sign

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.airvault.core.model.ChainId
import app.airvault.core.model.Coverage
import app.airvault.core.model.Danger
import app.airvault.core.model.checkLabels
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.FieldPath
import app.airvault.core.model.NetworkLabel
import app.airvault.core.model.Review
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.ReviewSection
import app.airvault.core.model.SectionStyle
import app.airvault.core.model.SummaryPart
import app.airvault.core.model.Warning
import app.lensly.app.AddressBookEntry
import app.lensly.app.AddressNotes
import app.lensly.app.addressKeyOf
import app.lensly.app.addressEnds
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LumenTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
// Native graphics, so text is measured with real font metrics. The legacy mode measures words at a few pixels.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val chain = ChainId("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k")
    private val receiver = "RECEIVERADDRESS00000000000000000000000000000000000000000"
    private val other = "OTHERADDRESS000000000000000000000000000000000000000000000"
    private val unlimited = "I understand this lets the spender take all of this token."
    private val second = "I understand Lensly cannot say what this does."

    private fun review(
        summary: List<SummaryPart> = listOf(SummaryPart.Text("Sends 1.000000 ALGO.")),
        sections: List<ReviewSection> = emptyList(),
        warnings: List<Warning> = emptyList(),
    ) = Review(
        title = "Review",
        chain = chain,
        network = NetworkLabel("Algorand Testnet", testnet = true, detail = null),
        summary = summary,
        facts = emptyList(),
        sections = sections,
        warnings = warnings,
        coverage = Coverage(decoded = emptySet(), excluded = emptyMap()),
    )

    private fun setScreen(review: Review, summaryIcon: (@Composable () -> Unit)? = null) {
        compose.setContent {
            LumenTheme {
                var ticked by remember { mutableStateOf(emptySet<String>()) }
                ReviewScreen(
                    review = review,
                    signer = SAMPLE_SIGNER,
                    addressNotes = emptyMap(),
                    addressChain = chain,
                    ticked = ticked,
                    onTick = { label, on -> ticked = if (on) ticked + label else ticked - label },
                    onApprove = {},
                    onReject = {},
                    summaryIcon = summaryIcon,
                )
            }
        }
    }

    @Test
    fun aCounterpartyIsNamedFromTheAddressBookAndAnUnnamedOneIsShownShort() {
        val entry = AddressBookEntry("entry-1", receiver, "Alice", null)
        val notes = mapOf(addressKeyOf(receiver) to AddressNotes(internal = null, entry = entry))
        val named = listOf(
            SummaryPart.Text("Send "),
            SummaryPart.Mono("1.5 USDC"),
            SummaryPart.Text(" to "),
            SummaryPart.Counterparty(receiver),
        )
        assertEquals("Send 1.5 USDC to \u2068Alice\u2069 (${addressEnds(receiver)})", summarySentence(named, notes, SAMPLE_SIGNER, FontFamily.Monospace, testPalette).text)
        val unnamed = named.dropLast(1) + SummaryPart.Counterparty(other)
        assertEquals(
            "Send 1.5 USDC to ${shortAddress(other)}",
            summarySentence(unnamed, notes, SAMPLE_SIGNER, FontFamily.Monospace, testPalette).text,
        )
    }

    @Test
    fun anArabicOrHebrewNameIsIsolatedAndAnAddressLikeNameIsNotShown() {
        val parts = listOf(SummaryPart.Text("Send "), SummaryPart.Mono("1.5 USDC"), SummaryPart.Text(" to "), SummaryPart.Counterparty(receiver))
        fun sentence(name: String): String {
            val notes = mapOf(addressKeyOf(receiver) to AddressNotes(internal = null, entry = AddressBookEntry("entry-1", receiver, name, null)))
            return summarySentence(parts, notes, SAMPLE_SIGNER, FontFamily.Monospace, testPalette).text
        }
        listOf("\u0645\u064F\u062D\u064E\u0645\u064E\u0651\u062F", "\u05D3\u05D5\u05D3").forEach { name ->
            // A direction character in the saved name is removed, so the two isolates are the only ones in the sentence.
            assertEquals("Send 1.5 USDC to \u2068$name\u2069 (${addressEnds(receiver)})", sentence("\u202E$name\u2067"))
        }
        assertEquals("Send 1.5 USDC to ${shortAddress(receiver)}", sentence("0x71C7 656E \u2026 976F"))
    }

    @Test
    fun approveIsEnabledOnAReviewWithNothingUnaccounted() {
        setScreen(review())
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsEnabled()
    }

    @Test
    fun approveStaysDisabledWhenADecodedFieldHasNoRow() {
        val coverage = Coverage(decoded = setOf(FieldPath("algorand.note")), excluded = emptyMap())
        setScreen(review().copy(coverage = coverage))
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()
    }

    @Test
    fun theTickHintSaysCheckboxOrCheckboxesByHowManyAreStillOwed() {
        assertNull(tickHint(0))
        assertEquals("Tick the checkbox above to sign", tickHint(1))
        assertEquals("Tick the checkboxes above to sign", tickHint(2))
    }

    @Test
    fun aCheckNamedByTwoRowsIsShownOnceAndTickedOnceForBoth() {
        val warnings = listOf(
            Warning(Danger.DANGER, "evm.unlimited", "Approve A is unlimited.", acknowledge = unlimited),
            Warning(Danger.DANGER, "evm.unlimited", "Approve B is unlimited.", acknowledge = unlimited),
        )
        assertEquals(listOf(unlimited), review(warnings = warnings).checkLabels())

        val ticks = mutableListOf<Pair<String, Boolean>>()
        val items = riskItems(warnings, ticked = emptySet()) { label, on -> ticks += label to on }
        assertEquals(listOf(unlimited, null), items.map { it.check?.label })
        items.first().check!!.onCheckedChange(true)
        assertEquals(listOf(unlimited to true), ticks)
        assertTrue(riskItems(warnings, ticked = setOf(unlimited)) { _, _ -> }.first().check!!.checked)
    }

    @Test
    fun aStepsSectionNumbersEachStepInOrder() {
        val steps = ReviewSection(
            heading = "Steps",
            rows = listOf(ReviewRow("Approve the spender", value = null, field = null), ReviewRow("Swap the tokens", value = null, field = null)),
            style = SectionStyle.Steps,
        )
        setScreen(review(sections = listOf(steps)))
        compose.onNodeWithText("1.").assertExists()
        compose.onNodeWithText("2.").assertExists()
        compose.onNodeWithText("Approve the spender").assertExists()
        compose.onNodeWithText("Swap the tokens").assertExists()
    }

    @Test
    fun aMessageShowsItsLabelAndItsTextWhole() {
        val message = "Sign in to Lensly, nonce 42"
        val row = ReviewRow("Message", DisplayValue.Message(message), FieldPath("evm.message"))
        setScreen(review(sections = listOf(ReviewSection("Transaction", listOf(row)))))
        compose.onNodeWithText("Message").assertExists()
        compose.onNodeWithText(message).assertExists()
    }

    @Test
    fun aDeeperRowIsIndentedFromTheRowsAboveItByItsDepth() {
        val rows = listOf(
            ReviewRow("Top row", DisplayValue.Text("a"), FieldPath("t.top")),
            ReviewRow("Middle row", DisplayValue.Text("b"), FieldPath("t.mid"), depth = 1),
            ReviewRow("Deep row", DisplayValue.Text("c"), FieldPath("t.deep"), depth = 2),
        )
        setScreen(review(sections = listOf(ReviewSection("Fields", rows))))
        val top = compose.onNodeWithText("Top row").fetchSemanticsNode().boundsInRoot.left
        val middle = compose.onNodeWithText("Middle row").fetchSemanticsNode().boundsInRoot.left
        val deep = compose.onNodeWithText("Deep row").fetchSemanticsNode().boundsInRoot.left
        assertTrue(middle > top, "a depth-1 row sits right of a depth-0 row")
        assertEquals(2 * (middle - top), deep - top, 0.5f, "a depth-2 row is indented twice the depth-1 step")
    }

    @Test
    fun aSummaryIconShowsBeforeTheSentence() {
        setScreen(review(), summaryIcon = { Box(Modifier.testTag("summary-icon").size(8.dp)) })
        compose.onNodeWithTag("summary-icon").assertExists()
        compose.onNodeWithText("Sends 1.000000 ALGO.").assertExists()
    }

    @Test
    fun aCheckShownByTwoRowsIsOneTickOnScreenAndTheHintCountsChecksNotRows() {
        val warnings = listOf(
            Warning(Danger.DANGER, "evm.unlimited", "Approve A is unlimited.", acknowledge = unlimited),
            Warning(Danger.DANGER, "evm.unlimited", "Approve B is unlimited.", acknowledge = unlimited),
            Warning(Danger.CAUTION, "evm.unknown", "Call C is unknown.", acknowledge = second),
        )
        setScreen(review(warnings = warnings))
        compose.scrollReviewToEnd()
        compose.onAllNodesWithText(unlimited).assertCountEquals(1)
        compose.onNodeWithText("Tick the checkboxes above to sign").assertExists()
        compose.onNodeWithText(unlimited).performClick()
        compose.onNodeWithText("Tick the checkbox above to sign").assertExists()
        compose.onNodeWithText(second).performClick()
        compose.onNodeWithText("Tick the checkbox above to sign").assertDoesNotExist()
    }
}
