package app.lensly.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.airvault.chains.bitcoin.signer.BtcReview
import app.airvault.chains.bitcoin.signer.BtcReviewInput
import app.airvault.chains.bitcoin.signer.BtcReviewOutput
import app.airvault.chains.bitcoin.signer.BtcWarning
import app.airvault.chains.bitcoin.signer.InputVerificationPolicy
import app.airvault.chains.bitcoin.signer.SigningSourcePolicy
import app.lensly.app.BITCOIN_FAMILY
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.ui.settings.BitcoinPolicyScreen
import app.lensly.ui.settings.FeeWarningScreen
import app.lensly.ui.sign.ReviewScreen
import app.lensly.ui.sign.SAMPLE_SIGNER
import app.lensly.ui.sign.bitcoinAcknowledgement
import app.lensly.ui.sign.bitcoinReviewOf
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Bitcoin review in its two states, and the Bitcoin policy settings at their defaults. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitcoinScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bitcoinReviewOnTestnet() = compose.snapshot("bitcoin_review_testnet") {
        BitcoinReviewAt(review = cleanReview(), confirmed = false, onConfirmedChange = {})
    }

    @Test
    fun bitcoinReviewWithWarnings() = compose.snapshot("bitcoin_review_warnings") {
        BitcoinReviewAt(review = warnedReview(), confirmed = false, onConfirmedChange = {})
    }

    @Test
    fun bitcoinReviewBlockedWhileItsDetailsAreFolded() = compose.snapshot("bitcoin_review_blocked") {
        // The request fits on screen, yet Hold to sign is off and says to open the details, where its one warning is read.
        BitcoinReviewAt(review = externalInputReview(), confirmed = false, onConfirmedChange = {})
    }

    @Test
    fun bitcoinReviewWithFeeWarnings() = compose.snapshot("bitcoin_review_fee_warnings") {
        BitcoinReviewAt(
            review = warnedReview().copy(
                feeWarnings = listOf(BtcWarning.HighFeeRate(285.7, 200), BtcWarning.HighFeeShare(41.7, 10)),
            ),
            confirmed = false,
            onConfirmedChange = {},
        )
    }

    @Test
    fun feeWarningsAtTheirDefaults() = compose.snapshot("fee_warnings_defaults") { FeeWarningsAtDefaults() }

    @Test
    @Config(qualifiers = SCREENSHOT_NIGHT_QUALIFIERS)
    fun feeWarningsAtTheirDefaultsNight() = compose.snapshot("fee_warnings_defaults_night") { FeeWarningsAtDefaults() }

    @Test
    fun bitcoinPolicyAtItsDefaults() = compose.snapshot("bitcoin_policy_defaults") { BitcoinPolicyAtDefaults() }

    @Test
    @Config(qualifiers = SCREENSHOT_NIGHT_QUALIFIERS)
    fun bitcoinPolicyAtItsDefaultsNight() = compose.snapshot("bitcoin_policy_defaults_night") { BitcoinPolicyAtDefaults() }

    /** The Bitcoin review as the sign flow shows it on testnet4 from a Native Segwit account, with its check held here. */
    @Composable
    private fun BitcoinReviewAt(review: BtcReview, confirmed: Boolean, onConfirmedChange: (Boolean) -> Unit) {
        ReviewScreen(
            review = bitcoinReviewOf(review, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT),
            signer = SAMPLE_SIGNER,
            addressNotes = emptyMap(),
            addressChain = BITCOIN_FAMILY,
            acknowledge = bitcoinAcknowledgement(review, confirmed, onConfirmedChange),
            onApprove = {},
            onReject = {},
        )
    }

    @Composable
    private fun FeeWarningsAtDefaults() {
        FeeWarningScreen(
            rate = 200,
            share = 10,
            onRate = {},
            onShare = {},
            onBack = {},
        )
    }

    @Composable
    private fun BitcoinPolicyAtDefaults() {
        BitcoinPolicyScreen(
            inputPolicy = InputVerificationPolicy.STRICT,
            signingSource = SigningSourcePolicy.ANY,
            ready = true,
            onInputPolicy = {},
            onSigningSource = {},
            onBack = {},
        )
    }
}

/** The account's path at receive index [index] of account 0 on testnet4, Native Segwit. */
private fun ownPath(index: Long): KeyPath = KeyPath(
    listOf(
        DeterministicWallet.hardened(84L),
        DeterministicWallet.hardened(1L),
        DeterministicWallet.hardened(0L),
        0L,
        index,
    ),
)

/** A request that spends one verified input of the account, pays a receiver and returns the change to the account. */
private fun cleanReview() = BtcReview(
    inputPolicy = InputVerificationPolicy.STRICT,
    inputs = listOf(BtcReviewInput(0, 100_000L, amountVerified = true, ownerPath = ownPath(0), sequence = 0xFFFFFFFDL)),
    outputs = listOf(
        BtcReviewOutput(0, "tb1qexampleexampleexampleexampleexample0", 30_000L, isOwnChange = false, changeIndex = null),
        BtcReviewOutput(1, "tb1qexampleexampleexampleexampleexample1", 60_000L, isOwnChange = true, changeIndex = 0),
    ),
    feeSats = 10_000L,
    requiresConfirmation = false,
    estimatedVsize = 141L,
    lockTime = 0L,
)

/** The clean request with a second input that another signer owns: a plain warning, which the owner reads in the details. */
private fun externalInputReview() = cleanReview().copy(
    inputs = listOf(
        BtcReviewInput(0, 100_000L, amountVerified = true, ownerPath = ownPath(0), sequence = 0xFFFFFFFDL),
        BtcReviewInput(1, 50_000L, amountVerified = true, ownerPath = null, sequence = 0xFFFFFFFDL),
    ),
    outputs = listOf(
        BtcReviewOutput(0, "tb1qexampleexampleexampleexampleexample0", 30_000L, isOwnChange = false, changeIndex = null),
        BtcReviewOutput(1, "tb1qexampleexampleexampleexampleexample1", 110_000L, isOwnChange = true, changeIndex = 0),
    ),
)

/**
 * A request whose input amount is not verified, with an input of another signer, and an output that names a key of the wallet but is
 * not its change. The review asks for a confirmation before it can be signed.
 */
private fun warnedReview() = BtcReview(
    inputPolicy = InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING,
    inputs = listOf(
        BtcReviewInput(0, 100_000L, amountVerified = false, ownerPath = ownPath(0), sequence = 0xFFFFFFFDL),
        BtcReviewInput(1, 50_000L, amountVerified = false, ownerPath = null, sequence = 0xFFFFFFFDL),
    ),
    outputs = listOf(
        BtcReviewOutput(0, "tb1qexampleexampleexampleexampleexample2", 120_000L, isOwnChange = false, changeIndex = null),
        BtcReviewOutput(
            1,
            "tb1qexampleexampleexampleexampleexample3",
            20_000L,
            isOwnChange = false,
            changeIndex = null,
            claimsOwnKey = true,
        ),
    ),
    feeSats = null,
    requiresConfirmation = true,
    estimatedVsize = 205L,
    lockTime = 0L,
)
