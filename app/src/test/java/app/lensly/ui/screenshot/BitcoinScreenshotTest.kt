package app.lensly.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcReview
import app.lensly.core.btc.BtcReviewInput
import app.lensly.core.btc.BtcReviewOutput
import app.lensly.core.btc.InputVerificationPolicy
import app.lensly.core.btc.ScriptType
import app.lensly.core.btc.SigningSourcePolicy
import app.lensly.ui.sign.BitcoinReviewScreen
import app.lensly.ui.settings.BitcoinPolicyScreen
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
        BitcoinReviewScreen(
            review = cleanReview(),
            network = BtcNetwork.TESTNET4,
            scriptType = ScriptType.NATIVE_SEGWIT,
            accountIndex = 0,
            confirmed = false,
            onConfirmedChange = {},
            onApprove = {},
            onReject = {},
        )
    }

    @Test
    fun bitcoinReviewWithWarnings() = compose.snapshot("bitcoin_review_warnings") {
        BitcoinReviewScreen(
            review = warnedReview(),
            network = BtcNetwork.TESTNET4,
            scriptType = ScriptType.NATIVE_SEGWIT,
            accountIndex = 1,
            confirmed = false,
            onConfirmedChange = {},
            onApprove = {},
            onReject = {},
        )
    }

    @Test
    fun bitcoinPolicyAtItsDefaults() = compose.snapshot("bitcoin_policy_defaults") {
        BitcoinPolicyScreen(
            inputPolicy = InputVerificationPolicy.STRICT,
            signingSource = SigningSourcePolicy.ANY,
            ready = true,
            onInputPolicy = {},
            onSigningSource = {},
            onSave = {},
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
    inputs = listOf(BtcReviewInput(0, 100_000L, amountVerified = true, ownerPath = ownPath(0))),
    outputs = listOf(
        BtcReviewOutput(0, "tb1qexampleexampleexampleexampleexample0", 30_000L, isOwnChange = false, changeIndex = null),
        BtcReviewOutput(1, "tb1qexampleexampleexampleexampleexample1", 60_000L, isOwnChange = true, changeIndex = 0),
    ),
    feeSats = 10_000L,
    requiresConfirmation = false,
    estimatedVsize = 141L,
)

/**
 * A request whose input amount is not verified, with an input of another signer, and an output that names a key of the wallet but is
 * not its change. The review asks for a confirmation before it can be signed.
 */
private fun warnedReview() = BtcReview(
    inputPolicy = InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING,
    inputs = listOf(
        BtcReviewInput(0, 100_000L, amountVerified = false, ownerPath = ownPath(0)),
        BtcReviewInput(1, 50_000L, amountVerified = false, ownerPath = null),
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
)
