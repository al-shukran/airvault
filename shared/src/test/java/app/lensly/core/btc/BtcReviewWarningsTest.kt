package app.lensly.core.btc

import fr.acinq.bitcoin.KeyPath
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class BtcReviewWarningsTest {
    private val seed = BitcoinVectors.seed()
    private val account = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)

    @Test
    fun aCleanReviewHasNoWarnings() {
        // A 1,000 sat fee on 60,000 sat sent is under the default share limit, and its rate is under the default rate limit.
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val review = BtcSigner(account).review(BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(account, 60_000L, 39_000L)))
        assertEquals(emptyList(), review.warnings())
    }

    @Test
    fun anInputOfAnotherSignerIsAnExternalInputWarning() {
        val foreign = BtcAccount.fromSeed(ByteArray(32) { 9 }, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)
        val spends = listOf(
            BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0),
            BtcFixtures.Spend(BtcFixtures.funding(foreign, 0, 0, 50_000L, salt = 2), foreign, 0, 0),
        )
        val review = BtcSigner(account).review(BtcFixtures.psbt(spends, BtcFixtures.paying(account, 120_000L, 20_000L)))
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
            inputs = listOf(
                BtcReviewInput(0, 100_000L, amountVerified = true, ownerPath = KeyPath(listOf(0L)), sequence = 0xFFFFFFFDL),
            ),
            outputs = listOf(
                BtcReviewOutput(0, "tb1q-example", 50_000L, isOwnChange = false, changeIndex = null, claimsOwnKey = true),
            ),
            feeSats = 50_000L,
            requiresConfirmation = false,
            estimatedVsize = 100L,
            lockTime = 0L,
        )
        assertEquals(listOf(BtcWarning.UnknownChange(1)), review.warnings())
        assertEquals(500.0, review.feeRateSatPerVb)
    }

    @Test
    fun aLockTimeThatAnInputEnforcesIsALockTimeWarning() {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        // A 1,000 sat fee keeps the fee warnings quiet, so the lock is the only warning.
        val psbt = BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(account, 60_000L, 39_000L), lockTime = 850_000L)
        val review = BtcSigner(account).review(psbt)
        assertEquals(850_000L, review.lockTime)
        // The default sequence signals replace-by-fee, and that is what the review shows for the input.
        assertTrue(review.inputs.single().replaceableByFee)
        assertTrue(review.lockTimeEnforced)
        assertEquals(listOf(BtcWarning.LockTime(850_000L)), review.warnings())
    }

    @Test
    fun aLockTimeThatEveryInputsFinalSequenceIgnoresIsNotWarned() {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val psbt = BtcFixtures.psbt(
            listOf(spend),
            BtcFixtures.paying(account, 60_000L, 39_000L),
            sequence = FINAL_SEQUENCE,
            lockTime = 850_000L,
        )
        val review = BtcSigner(account).review(psbt)
        assertEquals(850_000L, review.lockTime)
        assertFalse(review.inputs.single().replaceableByFee)
        assertFalse(review.lockTimeEnforced)
        assertEquals(emptyList(), review.warnings())
    }

    @Test
    fun aSequenceAtTheBip125LimitSignalsNoReplaceByFeeButStillEnforcesTheLockTime() {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val psbt = BtcFixtures.psbt(
            listOf(spend),
            BtcFixtures.paying(account, 30_000L, 60_000L),
            sequence = 0xFFFFFFFEL,
            lockTime = 850_000L,
        )
        val review = BtcSigner(account).review(psbt)
        assertFalse(review.inputs.single().replaceableByFee)
        assertTrue(review.lockTimeEnforced)
    }

    @Test
    fun aFeeAboveTheOwnersRateLimitIsAHighFeeRateWarning() {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val psbt = BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(account, 30_000L, 60_000L))
        val review = BtcSigner(account, feeThresholds = BtcFeeThresholds(highFeeRateSatPerVb = 1, feeSharePercent = 100))
            .review(psbt)
        assertEquals(1, review.warnings().filterIsInstance<BtcWarning.HighFeeRate>().size)
        assertTrue(review.warnings().none { it is BtcWarning.HighFeeShare })
    }

    @Test
    fun aFeeOfAnUnverifiedRequestHasNoFeeWarning() {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val psbt = BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(account, 30_000L, 60_000L), withPreviousTransaction = false)
        val review = BtcSigner(
            account,
            InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING,
            feeThresholds = BtcFeeThresholds(highFeeRateSatPerVb = 1, feeSharePercent = 1),
        ).review(psbt)
        assertEquals(emptyList(), review.feeWarnings)
    }
}
