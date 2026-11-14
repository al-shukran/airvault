package app.lensly.core.btc

import fr.acinq.bitcoin.KeyPath
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BtcReviewWarningsTest {
    private val seed = BitcoinVectors.seed()
    private val account = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)

    @Test
    fun aCleanReviewHasNoWarnings() {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val review = BtcSigner(account).review(BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(account, 30_000L, 60_000L)))
        assertEquals(emptyList(), review.warnings())
    }

    @Test
    fun anInputOfAnotherSignerIsAnExternalInputWarning() {
        val foreign = BtcAccount.fromSeed(ByteArray(32) { 9 }, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)
        val spends = listOf(
            BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0),
            BtcFixtures.Spend(BtcFixtures.funding(foreign, 0, 0, 50_000L, salt = 2), foreign, 0, 0),
        )
        val review = BtcSigner(account).review(BtcFixtures.psbt(spends, BtcFixtures.paying(account, 30_000L, 60_000L)))
        assertEquals(listOf(BtcWarning.ExternalInputs(1)), review.warnings())
    }

    @Test
    fun aSegwitAmountWithoutItsPreviousTransactionIsAnUnverifiedAmountWarning() {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val psbt = BtcFixtures.psbt(
            listOf(spend),
            BtcFixtures.paying(account, 30_000L, 60_000L),
            withPreviousTransaction = false,
        )
        val review = BtcSigner(account, InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING).review(psbt)
        assertTrue(review.requiresConfirmation)
        assertNull(review.feeSats)
        assertEquals(listOf(BtcWarning.UnverifiedAmounts(1)), review.warnings())
    }

    @Test
    fun anOutputThatClaimsAKeyOfTheWalletButIsNotItsChangeIsAnUnknownChangeWarning() {
        val review = BtcReview(
            inputPolicy = InputVerificationPolicy.STRICT,
            inputs = listOf(BtcReviewInput(0, 100_000L, amountVerified = true, ownerPath = KeyPath(listOf(0L)))),
            outputs = listOf(
                BtcReviewOutput(0, "tb1q-example", 50_000L, isOwnChange = false, changeIndex = null, claimsOwnKey = true),
            ),
            feeSats = 50_000L,
            requiresConfirmation = false,
            estimatedVsize = 100L,
        )
        assertEquals(listOf(BtcWarning.UnknownChange(1)), review.warnings())
        assertEquals(500.0, review.feeRateSatPerVb)
    }
}
