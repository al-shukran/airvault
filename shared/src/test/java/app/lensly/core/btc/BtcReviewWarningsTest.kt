package app.lensly.core.btc

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.psbt.DataEntry
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.airvault.chains.bitcoin.signer.BtcFeeThresholds
import app.airvault.chains.bitcoin.signer.BtcPsbt
import app.airvault.chains.bitcoin.signer.BtcReview
import app.airvault.chains.bitcoin.signer.BtcReviewInput
import app.airvault.chains.bitcoin.signer.BtcReviewOutput
import app.airvault.chains.bitcoin.signer.BtcWarning
import app.airvault.chains.bitcoin.signer.FINAL_SEQUENCE
import app.airvault.chains.bitcoin.signer.InputVerificationPolicy
import app.airvault.chains.bitcoin.signer.warnings
import app.lensly.core.btc.BtcFixtures.withInputEntries
import org.junit.Test

class BtcReviewWarningsTest {
    private val seed = BitcoinVectors.seed()
    private val account = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)

    @Test
    fun aCleanReviewWarnsOnlyThatItsInputsWereNotChecked() {
        // A 1,000 sat fee on 60,000 sat sent is under the default share limit, and its rate is under the default rate limit.
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val review = BtcFixtures.signerOf(account, seed).review(BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(account, 60_000L, 39_000L)))
        assertEquals(listOf(BtcWarning.RiskUnchecked), review.warnings())
    }

    @Test
    fun anInputOfAnotherSignerIsAnExternalInputWarning() {
        val foreign = BtcAccount.fromSeed(ByteArray(32) { 9 }, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)
        val spends = listOf(
            BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0),
            BtcFixtures.Spend(BtcFixtures.funding(foreign, 0, 0, 50_000L, salt = 2), foreign, 0, 0),
        )
        val review = BtcFixtures.signerOf(account, seed).review(BtcFixtures.psbt(spends, BtcFixtures.paying(account, 120_000L, 20_000L)))
        assertEquals(listOf(BtcWarning.RiskUnchecked, BtcWarning.ExternalInputs(1)), review.warnings())
    }

    @Test
    fun aSegwitAmountWithoutItsPreviousTransactionIsAnUnverifiedAmountWarning() {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val psbt = BtcFixtures.psbt(
            listOf(spend),
            BtcFixtures.paying(account, 30_000L, 60_000L),
            withPreviousTransaction = false,
        )
        val review = BtcFixtures.signerOf(account, seed, InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING).review(psbt)
        assertTrue(review.requiresConfirmation)
        assertNull(review.feeSats)
        assertEquals(listOf(BtcWarning.RiskUnchecked, BtcWarning.UnverifiedAmounts(1)), review.warnings())
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
        assertEquals(listOf(BtcWarning.RiskUnchecked, BtcWarning.UnknownChange(1)), review.warnings())
        assertEquals(500.0, review.feeRateSatPerVb)
    }

    @Test
    fun aSubDustOutputIsADustWarningAndAnOpReturnIsNot() {
        val review = BtcReview(
            inputPolicy = InputVerificationPolicy.STRICT,
            inputs = listOf(
                BtcReviewInput(0, 100_000L, amountVerified = true, ownerPath = KeyPath(listOf(0L)), sequence = 0xFFFFFFFDL),
            ),
            outputs = listOf(
                BtcReviewOutput(0, "tb1q-example", 545L, isOwnChange = false, changeIndex = null),
                // The output at the dust limit is a payment the network relays, and an OP_RETURN has no address to pay.
                BtcReviewOutput(1, "tb1q-example", 546L, isOwnChange = false, changeIndex = null),
                BtcReviewOutput(2, null, 0L, isOwnChange = false, changeIndex = null),
            ),
            feeSats = 99_455L,
            requiresConfirmation = false,
            estimatedVsize = 100L,
            lockTime = 0L,
        )
        assertEquals(listOf(BtcWarning.RiskUnchecked, BtcWarning.DustOutputs(1)), review.warnings())
    }

    @Test
    fun aLockTimeThatAnInputEnforcesIsALockTimeWarning() {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        // A 1,000 sat fee keeps the fee warnings quiet, so the lock is the only warning.
        val psbt = BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(account, 60_000L, 39_000L), lockTime = 850_000L)
        val review = BtcFixtures.signerOf(account, seed).review(psbt)
        assertEquals(850_000L, review.lockTime)
        // The default sequence signals replace-by-fee, and that is what the review shows for the input.
        assertTrue(review.inputs.single().replaceableByFee)
        assertTrue(review.lockTimeEnforced)
        assertEquals(listOf(BtcWarning.RiskUnchecked, BtcWarning.LockTime(850_000L)), review.warnings())
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
        val review = BtcFixtures.signerOf(account, seed).review(psbt)
        assertEquals(850_000L, review.lockTime)
        assertFalse(review.inputs.single().replaceableByFee)
        assertFalse(review.lockTimeEnforced)
        assertEquals(listOf(BtcWarning.RiskUnchecked), review.warnings())
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
        val review = BtcFixtures.signerOf(account, seed).review(psbt)
        assertFalse(review.inputs.single().replaceableByFee)
        assertTrue(review.lockTimeEnforced)
    }

    @Test
    fun aFeeAboveTheOwnersRateLimitIsAHighFeeRateWarning() {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val psbt = BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(account, 30_000L, 60_000L))
        val review = BtcFixtures.signerOf(account, seed, feeThresholds = BtcFeeThresholds(highFeeRateSatPerVb = 1, feeSharePercent = 100))
            .review(psbt)
        assertEquals(1, review.warnings().filterIsInstance<BtcWarning.HighFeeRate>().size)
        assertTrue(review.warnings().none { it is BtcWarning.HighFeeShare })
    }

    @Test
    fun aFeeOfAnUnverifiedRequestHasNoFeeWarning() {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val psbt = BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(account, 30_000L, 60_000L), withPreviousTransaction = false)
        val review = BtcFixtures.signerOf(
            account,
            seed,
            InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING,
            feeThresholds = BtcFeeThresholds(highFeeRateSatPerVb = 1, feeSharePercent = 1),
        ).review(psbt)
        assertEquals(emptyList(), review.feeWarnings)
    }

    @Test
    fun aMarkedInputIsADangerWarningOfItsOwn() {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val psbt = BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(account, 60_000L, 39_000L))
            .withInputEntries(0, listOf(BtcPsbt.RISK_MARK))
        val review = BtcFixtures.signerOf(account, seed).review(psbt)
        // Every input is marked, so no unchecked-input note is left.
        assertEquals(listOf(BtcWarning.RiskMarkedInput(0)), review.warnings())
    }

    @Test
    fun aMarkedInputIsADangerRowAndTheOtherInputsGetOneCaution() {
        val spends = listOf(
            BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0),
            BtcFixtures.Spend(BtcFixtures.funding(account, 0, 1, 50_000L, salt = 2), account, 0, 1),
        )
        val psbt = BtcFixtures.psbt(spends, BtcFixtures.paying(account, 120_000L, 29_000L))
            .withInputEntries(1, listOf(BtcPsbt.RISK_MARK))
        val review = BtcFixtures.signerOf(account, seed).review(psbt)
        assertEquals(listOf(BtcWarning.RiskMarkedInput(1), BtcWarning.RiskUnchecked), review.warnings())
    }

    @Test
    fun aPsbtWithAMalformedOrOversizeMarkIsUnmarkedAndShowsTheCaution() {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val base = BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(account, 60_000L, 39_000L))
        val mark = BtcPsbt.RISK_MARK
        val key = mark.key
        val cases = listOf(
            "a value of another byte" to listOf(DataEntry(key, ByteVector(byteArrayOf(0x02)))),
            "an empty value" to listOf(DataEntry(key, ByteVector.empty)),
            "a value of two bytes" to listOf(DataEntry(key, ByteVector(byteArrayOf(0x01, 0x00)))),
            "an oversize value" to listOf(DataEntry(key, ByteVector(ByteArray(64 * 1024) { 0x01 }))),
            "another subtype" to listOf(DataEntry(ByteVector(byteArrayOf(0xFC.toByte(), 0x08) + "airvault".encodeToByteArray() + byteArrayOf(0x02)), mark.value)),
            "key data after the subtype" to listOf(DataEntry(ByteVector(key.toByteArray() + byteArrayOf(0x00)), mark.value)),
            "an oversize key" to listOf(DataEntry(ByteVector(key.toByteArray() + ByteArray(4096)), mark.value)),
        )
        for ((label, entries) in cases) {
            val review = BtcFixtures.signerOf(account, seed).review(base.withInputEntries(0, entries))
            assertEquals(listOf(BtcWarning.RiskUnchecked), review.warnings(), label)
        }
    }

    @Test
    fun aRepeatedMarkStillMarksItsInput() {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val psbt = BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(account, 60_000L, 39_000L))
            .withInputEntries(0, listOf(BtcPsbt.RISK_MARK, BtcPsbt.RISK_MARK))
        val review = BtcFixtures.signerOf(account, seed).review(psbt)
        assertEquals(listOf(BtcWarning.RiskMarkedInput(0)), review.warnings())
    }
}
