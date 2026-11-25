package app.lensly.ui.sign

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.lensly.app.BITCOIN_FAMILY
import app.lensly.app.addressStart
import app.lensly.app.isolated
import app.lensly.core.btc.BtcNetwork
import app.airvault.chains.bitcoin.signer.BtcReview
import app.airvault.chains.bitcoin.signer.BtcReviewInput
import app.airvault.chains.bitcoin.signer.BtcReviewOutput
import app.airvault.chains.bitcoin.signer.InputVerificationPolicy
import app.lensly.core.btc.ScriptType
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.sign.SAMPLE_SIGNER
import app.lensly.ui.theme.LumenTheme
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.TxHash
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Bitcoin review's gate and its layout: a request with a warning is read in its details before it can sign, the check on unverified
 * amounts sits inside the one banner, and the details start collapsed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitcoinReviewScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Shows [review]. The ticks are held here, as the sign flow holds them, so a tick changes the gate. A [small] window is short enough that
     * opened details run past its bottom, so the review must be scrolled to its end.
     */
    private fun show(review: BtcReview, small: Boolean = false) {
        compose.setContent {
            LumenTheme {
                Box(modifier = if (small) Modifier.size(width = 360.dp, height = 640.dp) else Modifier.fillMaxSize()) {
                    var ticked by remember { mutableStateOf(emptySet<String>()) }
                    ReviewScreen(
                        review = bitcoinReviewOf(review, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT, SAMPLE_SIGNER.address),
                        signer = SAMPLE_SIGNER,
                        addressNotes = emptyMap(),
                        addressChain = BITCOIN_FAMILY,
                        ticked = ticked,
                        onTick = { label, on -> ticked = if (on) ticked + label else ticked - label },
                        onApprove = {},
                        onReject = {},
                    )
                }
            }
        }
    }

    @Test
    fun theUnverifiedAmountsAskForAStandardCheckboxInsideTheBanner() {
        show(unverifiedRequest())

        compose.onNodeWithText("3 warnings — review before signing").assertExists()
        compose.onNodeWithText("I have checked the unverified input amounts").assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).assertExists()
    }

    @Test
    fun theHoldControlStaysOffUntilTheListIsReadAndTheCheckIsTicked() {
        show(unverifiedRequest(), small = true)

        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()

        compose.onNodeWithText("I have checked the unverified input amounts").performScrollTo().performClick()
        compose.onNodeWithText("Scroll to the end to review everything").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()

        compose.scrollReviewToEnd()
        compose.onNodeWithText("Scroll to the end to review everything").assertDoesNotExist()
        compose.onNodeWithText("Tick the checkbox above to sign").assertDoesNotExist()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsEnabled()
    }

    @Test
    fun aShortRequestWithAWarningShowsItsDetailsAndCanBeSignedOnceItFits() {
        // The request fits on screen, and its warnings have no check, so the fit is the whole read.
        show(externalInputRequest())

        compose.onNodeWithText("2 warnings — review before signing").assertExists()
        compose.onNodeWithText("Inputs").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsEnabled()
    }

    @Test
    fun aRequestWithAWarningSignsOnceItsListIsScrolledToTheEnd() {
        show(externalInputRequest(), small = true)

        compose.onNodeWithText("Scroll to the end to review everything").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()

        compose.scrollReviewToEnd()
        compose.onNodeWithText("Scroll to the end to review everything").assertDoesNotExist()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsEnabled()
    }

    @Test
    fun aRequestWithNoMarkedInputSaysItsInputsWereNotCheckedAndShowsEveryInputAtOnce() {
        show(cleanRequest())

        compose.onNodeWithText("1 warning — review before signing").assertExists()
        compose.onNodeWithText("Inputs were not checked for inscriptions or runes.").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsEnabled()
        compose.onNodeWithText("Input 0").assertExists()
    }

    @Test
    fun aMarkedInputIsADangerRowThatTheOwnerTicksBeforeItSigns() {
        show(riskMarkedRequest())

        compose.onNodeWithText("1 warning — review before signing").assertExists()
        val outPoint = outPointOf(0).txid
        compose.onNodeWithText("Input 0 ($outPoint:0) is marked as carrying an inscription or rune. Spending it may burn it.").assertExists()
        compose.onNodeWithText("I understand spending 1 marked input(s) may burn any inscription or rune they carry: $outPoint:0.").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()
    }

    @Test
    fun aLongRequestWithOnlyUncheckedInputsStillNeedsItsEndReadBeforeItSigns() {
        // The request is long enough to scroll, so signing waits for the scroll, as it always has.
        show(cleanRequest(), small = true)

        compose.onNodeWithText("Scroll to the end to review everything").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()

        compose.scrollReviewToEnd()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsEnabled()
    }

    @Test
    fun theSummarySaysWhatIsSentAndToWhomWithTheTotal() {
        show(cleanRequest())

        compose.onNodeWithText("Send 0.0003 BTC from ${isolated("OfPixelBlaze")} (${addressStart(SAMPLE_SIGNER.address)}) to tb1qexampleexampleexampleexampleexample0?").assertExists()
        compose.onNodeWithText("Total").assertExists()
    }

    @Test
    fun theSummaryNamesALongOutputAddressInFull() {
        // A taproot address is 62 characters. The summary sentence keeps all of them, so a look-alike cannot pass on its ends alone.
        val taproot = "tb1p" + "q".repeat(58)
        show(
            cleanRequest().copy(
                outputs = listOf(
                    BtcReviewOutput(0, taproot, 30_000L, isOwnChange = false, changeIndex = null),
                    BtcReviewOutput(1, "tb1qexampleexampleexampleexampleexample1", 60_000L, isOwnChange = true, changeIndex = 0),
                ),
            ),
        )

        compose.onNodeWithText("Send 0.0003 BTC from ${isolated("OfPixelBlaze")} (${addressStart(SAMPLE_SIGNER.address)}) to $taproot?").assertExists()
    }

    @Test
    fun aLockTimeThatTheInputsEnforceIsShownAndWarned() {
        show(cleanRequest().copy(lockTime = 850_000L))

        compose.onNodeWithText("Block 850000").assertExists()
        compose.onNodeWithText("The transaction cannot confirm before block 850000.").assertExists()
        compose.onNodeWithText("0xFFFFFFFD, replace-by-fee on").assertExists()
    }

    @Test
    fun aLockTimeThatFinalInputsIgnoreIsShownWithNoLockWarning() {
        val finalInput = cleanRequest().inputs.single().copy(sequence = 0xFFFFFFFFL)
        show(cleanRequest().copy(inputs = listOf(finalInput), lockTime = 850_000L))

        compose.onNodeWithText("Block 850000").assertExists()
        compose.onNodeWithText("Not enforced, because every input is final.").assertExists()
        compose.onNodeWithText("The transaction cannot confirm before block 850000.").assertDoesNotExist()
    }
}

/** The output that input [index] of these requests spends: each input spends its own transaction's first output. */
private fun outPointOf(index: Int): OutPoint = OutPoint(TxHash(ByteVector32(ByteArray(32) { (index + 1).toByte() })), 0L)

/** The account's path at receive index [index] of account 0 on testnet4, Native Segwit. */
private fun ownedPath(index: Long): KeyPath = KeyPath(
    listOf(
        DeterministicWallet.hardened(84L),
        DeterministicWallet.hardened(1L),
        DeterministicWallet.hardened(0L),
        0L,
        index,
    ),
)

/** A request that spends one verified input of the account, pays a receiver, and returns the change to the account. */
private fun cleanRequest() = BtcReview(
    inputPolicy = InputVerificationPolicy.STRICT,
    inputs = listOf(BtcReviewInput(0, outPointOf(0), 100_000L, amountVerified = true, ownerPath = ownedPath(0), sequence = 0xFFFFFFFDL)),
    outputs = listOf(
        BtcReviewOutput(0, "tb1qexampleexampleexampleexampleexample0", 30_000L, isOwnChange = false, changeIndex = null),
        BtcReviewOutput(1, "tb1qexampleexampleexampleexampleexample1", 60_000L, isOwnChange = true, changeIndex = 0),
    ),
    feeSats = 10_000L,
    requiresConfirmation = false,
    estimatedVsize = 141L,
    lockTime = 0L,
)

/**
 * The clean request with a second input that another signer owns. Its warnings are plain ones, with no check to tick, so the owner reads
 * them in the details alone.
 */
private fun externalInputRequest() = cleanRequest().copy(
    inputs = listOf(
        BtcReviewInput(0, outPointOf(0), 100_000L, amountVerified = true, ownerPath = ownedPath(0), sequence = 0xFFFFFFFDL),
        BtcReviewInput(1, outPointOf(1), 50_000L, amountVerified = true, ownerPath = null, sequence = 0xFFFFFFFDL),
    ),
    outputs = listOf(
        BtcReviewOutput(0, "tb1qexampleexampleexampleexampleexample0", 30_000L, isOwnChange = false, changeIndex = null),
        BtcReviewOutput(1, "tb1qexampleexampleexampleexampleexample1", 110_000L, isOwnChange = true, changeIndex = 0),
    ),
)

/** The clean request with its input marked as carrying an inscription or a rune: a DANGER row that the owner must tick before it signs. */
private fun riskMarkedRequest() = cleanRequest().copy(
    inputs = listOf(BtcReviewInput(0, outPointOf(0), 100_000L, amountVerified = true, ownerPath = ownedPath(0), sequence = 0xFFFFFFFDL, riskMarked = true)),
)

/**
 * A request with an unverified amount of its own and an input of another signer. It holds three warnings: the unverified amount, which the
 * owner must check before the review can be signed, the input of another signer, and the note that no input was checked for inscriptions.
 */
private fun unverifiedRequest() = BtcReview(
    inputPolicy = InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING,
    inputs = listOf(
        BtcReviewInput(0, outPointOf(0), 100_000L, amountVerified = false, ownerPath = ownedPath(0), sequence = 0xFFFFFFFDL),
        BtcReviewInput(1, outPointOf(1), 50_000L, amountVerified = true, ownerPath = null, sequence = 0xFFFFFFFDL),
    ),
    outputs = listOf(
        BtcReviewOutput(0, "tb1qexampleexampleexampleexampleexample2", 120_000L, isOwnChange = false, changeIndex = null),
    ),
    feeSats = null,
    requiresConfirmation = true,
    estimatedVsize = 205L,
    lockTime = 0L,
)
