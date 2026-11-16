package app.lensly.ui.sign

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcReview
import app.lensly.core.btc.BtcReviewInput
import app.lensly.core.btc.BtcReviewOutput
import app.lensly.core.btc.InputVerificationPolicy
import app.lensly.core.btc.ScriptType
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Bitcoin review's gate and its layout: the check that unlocks signing sits inside the one banner, and the details start collapsed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitcoinReviewScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** Shows [review]. The check is held by the screen the way the wallet's route holds it, so a tick changes the gate. */
    private fun show(review: BtcReview) {
        compose.setContent {
            LenslyTheme {
                var confirmed by remember { mutableStateOf(false) }
                BitcoinReviewScreen(
                    review = review,
                    network = BtcNetwork.TESTNET4,
                    scriptType = ScriptType.NATIVE_SEGWIT,
                    accountIndex = 0,
                    confirmed = confirmed,
                    onConfirmedChange = { confirmed = it },
                    onApprove = {},
                    onReject = {},
                )
            }
        }
    }

    @Test
    fun theUnverifiedAmountsAskForAStandardCheckboxInsideTheBanner() {
        show(unverifiedRequest())

        compose.onNodeWithText("2 warnings — review before signing").assertExists()
        compose.onNodeWithText("I have checked the unverified input amounts").assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).assertExists()
    }

    @Test
    fun theHoldControlStaysOffUntilTheCheckIsTickedAndSaysWhy() {
        show(unverifiedRequest())

        compose.onNodeWithText("Tick the checkbox above to sign").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()

        compose.onNodeWithText("I have checked the unverified input amounts").performClick()

        compose.onNodeWithText("Tick the checkbox above to sign").assertDoesNotExist()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsEnabled()
    }

    @Test
    fun aRequestWithNoWarningsSaysSoAndKeepsItsDetailsCollapsed() {
        show(cleanRequest())

        compose.onNodeWithText("No warnings").assertExists()
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsEnabled()
        compose.onNodeWithText("Input 0").assertDoesNotExist()

        compose.onNodeWithText("Inputs").performClick()

        compose.onNodeWithText("Input 0").assertExists()
    }

    @Test
    fun theSummarySaysWhatIsSentAndToWhomWithTheTotal() {
        show(cleanRequest())

        compose.onNodeWithText("Send 0.0003 BTC to tb1qexampl...ample0").assertExists()
        compose.onNodeWithText("Total").assertExists()
    }
}

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
    inputs = listOf(BtcReviewInput(0, 100_000L, amountVerified = true, ownerPath = ownedPath(0))),
    outputs = listOf(
        BtcReviewOutput(0, "tb1qexampleexampleexampleexampleexample0", 30_000L, isOwnChange = false, changeIndex = null),
        BtcReviewOutput(1, "tb1qexampleexampleexampleexampleexample1", 60_000L, isOwnChange = true, changeIndex = 0),
    ),
    feeSats = 10_000L,
    requiresConfirmation = false,
    estimatedVsize = 141L,
)

/**
 * A request with an unverified amount of its own and an input of another signer. It holds two warnings, and the owner must check the
 * unverified amount before the review can be signed.
 */
private fun unverifiedRequest() = BtcReview(
    inputPolicy = InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING,
    inputs = listOf(
        BtcReviewInput(0, 100_000L, amountVerified = false, ownerPath = ownedPath(0)),
        BtcReviewInput(1, 50_000L, amountVerified = true, ownerPath = null),
    ),
    outputs = listOf(
        BtcReviewOutput(0, "tb1qexampleexampleexampleexampleexample2", 120_000L, isOwnChange = false, changeIndex = null),
    ),
    feeSats = null,
    requiresConfirmation = true,
    estimatedVsize = 205L,
)
