package app.lensly.core.btc

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Input
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class BtcSignerRefusalTest {
    private val seed = BitcoinVectors.seed()
    private val testnet4 = BtcNetwork.TESTNET4
    private val native = BtcAccount.fromSeed(seed, testnet4, ScriptType.NATIVE_SEGWIT)

    @Test
    fun strictRefusesAnInputWithoutItsPreviousTransaction() {
        for (type in listOf(ScriptType.NATIVE_SEGWIT, ScriptType.TAPROOT, ScriptType.NESTED_SEGWIT)) {
            val account = BtcAccount.fromSeed(seed, testnet4, type)
            val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
            val psbt = BtcFixtures.psbt(listOf(spend), outputs(account, 90_000L), withPreviousTransaction = false)
            assertRefusal(BtcRefusal.MissingPreviousTransaction(0)) { BtcSigner(account).review(psbt) }
            assertRefusal(BtcRefusal.MissingPreviousTransaction(0)) { BtcSigner(account).sign(psbt) }
        }
    }

    @Test
    fun aPreviousTransactionForAnotherOutputIsRefused() {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(native, 0, 0, 100_000L), native, 0, 0)
        val other = BtcFixtures.funding(native, 0, 0, 100_000L, salt = 9)
        val psbt = BtcFixtures.psbt(listOf(spend), outputs(native, 90_000L)).copy(inputs = listOf(legacyInput(other)))
        assertRefusal(BtcRefusal.PreviousTransactionMismatch(0)) { BtcSigner(native).review(psbt) }
    }

    @Test
    fun aWitnessUtxoThatDisagreesWithThePreviousTransactionIsRefused() {
        val funding = BtcFixtures.funding(native, 0, 0, 100_000L)
        val spend = BtcFixtures.Spend(funding, native, 0, 0)
        val lying = Input.WitnessInput.PartiallySignedWitnessInput(
            txOut = TxOut(Satoshi(100_001L), funding.txOut[0].publicKeyScript),
            nonWitnessUtxo = funding,
            sighashType = null,
            partialSigs = emptyMap(),
            derivationPaths = emptyMap(),
            redeemScript = null,
            witnessScript = null,
            ripemd160 = emptySet(),
            sha256 = emptySet(),
            hash160 = emptySet(),
            hash256 = emptySet(),
            taprootKeySignature = null,
            taprootDerivationPaths = emptyMap(),
            taprootInternalKey = null,
            unknown = emptyList(),
        )
        val psbt = BtcFixtures.psbt(listOf(spend), outputs(native, 90_000L)).copy(inputs = listOf(lying))
        assertRefusal(BtcRefusal.PreviousTransactionMismatch(0)) { BtcSigner(native).review(psbt) }
    }

    @Test
    fun aPrevoutOfAnotherScriptTypeIsRefused() {
        val legacy = BtcAccount.fromSeed(seed, testnet4, ScriptType.LEGACY)
        val taproot = BtcAccount.fromSeed(seed, testnet4, ScriptType.TAPROOT)
        for (source in listOf(legacy, taproot)) {
            val spend = BtcFixtures.Spend(BtcFixtures.funding(source, 0, 0, 100_000L), source, 0, 0)
            val psbt = BtcFixtures.psbt(listOf(spend), outputs(native, 90_000L))
            assertRefusal(BtcRefusal.ScriptTypeNotUsed(0)) { BtcSigner(native).review(psbt) }
        }
    }

    @Test
    fun companionOnlyRefusesARequestWithoutTheMarkerAndAcceptsOneWithIt() {
        val signer = BtcSigner(native, sourcePolicy = SigningSourcePolicy.COMPANION_ONLY)
        val spend = BtcFixtures.Spend(BtcFixtures.funding(native, 0, 0, 100_000L), native, 0, 0)
        val psbt = BtcFixtures.psbt(listOf(spend), outputs(native, 90_000L))
        assertRefusal(BtcRefusal.SourceExcluded) { signer.review(psbt) }
        assertRefusal(BtcRefusal.SourceExcluded) { signer.sign(psbt) }

        val marked = BtcPsbt.withCompanionMarker(psbt)
        assertEquals(10_000L, signer.review(marked).feeSats)
        assertTrue(signer.sign(marked).psbt.inputs[0].partialSigs.isNotEmpty())
    }

    @Test
    fun anyOnlineSideIsAcceptedUnderTheDefaultSourcePolicy() {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(native, 0, 0, 100_000L), native, 0, 0)
        val psbt = BtcFixtures.psbt(listOf(spend), outputs(native, 90_000L))
        assertEquals(10_000L, BtcSigner(native).review(psbt).feeSats)
    }

    @Test
    fun sighashOtherThanAllIsRefusedForAnOwnedInput() {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(native, 0, 0, 100_000L), native, 0, 0)
        val none = BtcFixtures.psbt(listOf(spend), outputs(native, 90_000L), sighash = SigHash.SIGHASH_NONE)
        assertRefusal(BtcRefusal.SighashNotAll(0)) { BtcSigner(native).sign(none) }
        val all = BtcFixtures.psbt(listOf(spend), outputs(native, 90_000L), sighash = SigHash.SIGHASH_ALL)
        assertTrue(BtcSigner(native).sign(all).psbt.inputs[0].partialSigs.isNotEmpty())
    }

    @Test
    fun aDerivationThatDoesNotProduceTheSpentScriptIsRefused() {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(native, 0, 0, 100_000L), native, 0, 0, claimedIndex = 1)
        val psbt = BtcFixtures.psbt(listOf(spend), outputs(native, 90_000L))
        assertRefusal(BtcRefusal.InconsistentOwnership(0)) { BtcSigner(native).review(psbt) }
    }

    @Test
    fun aDerivationUnderAnotherAccountIsRefused() {
        val second = BtcAccount.fromSeed(seed, testnet4, ScriptType.NATIVE_SEGWIT, accountIndex = 1)
        val spend = BtcFixtures.Spend(BtcFixtures.funding(second, 0, 0, 100_000L), second, 0, 0)
        val psbt = BtcFixtures.psbt(listOf(spend), outputs(native, 90_000L))
        assertRefusal(BtcRefusal.InputOutsideAccount(0)) { BtcSigner(native).review(psbt) }
    }

    @Test
    fun outputsWorthMoreThanTheVerifiedInputsAreRefused() {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(native, 0, 0, 100_000L), native, 0, 0)
        val psbt = BtcFixtures.psbt(listOf(spend), outputs(native, 200_000L))
        assertRefusal(BtcRefusal.OutputsExceedInputs) { BtcSigner(native).review(psbt) }
    }

    @Test
    fun aRequestWithNoOwnedInputIsNotSigned() {
        val foreign = BtcAccount.fromSeed(ByteArray(16) { 9 }, testnet4, ScriptType.NATIVE_SEGWIT)
        val spend = BtcFixtures.Spend(BtcFixtures.funding(foreign, 0, 0, 100_000L), foreign, 0, 0)
        val psbt = BtcFixtures.psbt(listOf(spend), outputs(native, 90_000L))
        assertRefusal(BtcRefusal.NothingToSign) { BtcSigner(native).sign(psbt) }
    }

    @Test
    fun warningPolicyNeedsConfirmationBeforeSigningUnverifiedSegwitAmounts() {
        val signer = BtcSigner(native, inputPolicy = InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING)
        val funding = BtcFixtures.funding(native, 0, 0, 100_000L)
        val spend = BtcFixtures.Spend(funding, native, 0, 0)
        val psbt = BtcFixtures.psbt(listOf(spend), outputs(native, 90_000L), withPreviousTransaction = false)

        val review = signer.review(psbt)
        assertFalse(review.inputs[0].amountVerified)
        assertEquals(100_000L, review.inputs[0].amountSats)
        assertNull(review.feeSats, "a fee is never taken from unverified amounts")
        assertTrue(review.requiresConfirmation)
        assertRefusal(BtcRefusal.ConfirmationRequired) { signer.sign(psbt) }

        val signed = signer.sign(psbt, unverifiedAmountsConfirmed = true).psbt
        val tx = BtcFixtures.finalize(signed, listOf(spend))
        assertTrue(BtcFixtures.verifies(tx, listOf(funding.txOut[0])))
    }

    @Test
    fun relaxedPolicyAcceptsWitnessOnlySegwitWithoutConfirmation() {
        val signer = BtcSigner(native, inputPolicy = InputVerificationPolicy.LEGACY_STRICT_SEGWIT_RELAXED)
        val funding = BtcFixtures.funding(native, 0, 0, 100_000L)
        val spend = BtcFixtures.Spend(funding, native, 0, 0)
        val psbt = BtcFixtures.psbt(listOf(spend), outputs(native, 90_000L), withPreviousTransaction = false)

        val review = signer.review(psbt)
        assertFalse(review.inputs[0].amountVerified, "the amount is still shown as unverified")
        assertFalse(review.requiresConfirmation)
        assertNull(review.feeSats)
        val tx = BtcFixtures.finalize(signer.sign(psbt).psbt, listOf(spend))
        assertTrue(BtcFixtures.verifies(tx, listOf(funding.txOut[0])))
    }

    @Test
    fun witnessOnlyLegacyInputIsRefusedUnderEveryWeakerPolicy() {
        val legacy = BtcAccount.fromSeed(seed, testnet4, ScriptType.LEGACY)
        val funding = BtcFixtures.funding(legacy, 0, 0, 100_000L)
        val spend = BtcFixtures.Spend(funding, legacy, 0, 0)
        val legacyOutputs = listOf(TxOut(Satoshi(90_000L), ByteVector(legacy.scriptPubKey(0, 5))))
        val witnessOnly = BtcFixtures.psbt(listOf(spend), legacyOutputs).copy(
            inputs = listOf(
                Input.WitnessInput.PartiallySignedWitnessInput(
                    txOut = funding.txOut[0],
                    nonWitnessUtxo = null,
                    sighashType = null,
                    partialSigs = emptyMap(),
                    derivationPaths = emptyMap(),
                    redeemScript = null,
                    witnessScript = null,
                    ripemd160 = emptySet(),
                    sha256 = emptySet(),
                    hash160 = emptySet(),
                    hash256 = emptySet(),
                    taprootKeySignature = null,
                    taprootDerivationPaths = emptyMap(),
                    taprootInternalKey = null,
                    unknown = emptyList(),
                ),
            ),
        )
        for (policy in listOf(InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING, InputVerificationPolicy.LEGACY_STRICT_SEGWIT_RELAXED)) {
            assertRefusal(BtcRefusal.MissingPreviousTransaction(0)) { BtcSigner(legacy, inputPolicy = policy).review(witnessOnly) }
        }
    }

    @Test
    fun theTwoPoliciesAreIndependentOfEachOther() {
        // A companion-only signer under the relaxed input policy still refuses a request without the marker.
        val signer = BtcSigner(
            native,
            inputPolicy = InputVerificationPolicy.LEGACY_STRICT_SEGWIT_RELAXED,
            sourcePolicy = SigningSourcePolicy.COMPANION_ONLY,
        )
        val spend = BtcFixtures.Spend(BtcFixtures.funding(native, 0, 0, 100_000L), native, 0, 0)
        val psbt = BtcFixtures.psbt(listOf(spend), outputs(native, 90_000L))
        assertRefusal(BtcRefusal.SourceExcluded) { signer.review(psbt) }
        assertFalse(assertFailsWith<BtcRefusedException> { signer.review(psbt) }.refusal.reason.isBlank())
    }

    private fun outputs(account: BtcAccount, paid: Long): List<TxOut> = listOf(
        TxOut(Satoshi(paid), ByteVector(account.scriptPubKey(0, 5))),
    )

    /** An input that carries [previous] as its non_witness_utxo and nothing else. */
    private fun legacyInput(previous: Transaction): Input = Input.NonWitnessInput.PartiallySignedNonWitnessInput(
        inputTx = previous,
        outputIndex = 0,
        sighashType = null,
        partialSigs = emptyMap(),
        derivationPaths = emptyMap(),
        redeemScript = null,
        ripemd160 = emptySet(),
        sha256 = emptySet(),
        hash160 = emptySet(),
        hash256 = emptySet(),
        unknown = emptyList(),
    )

    private fun assertRefusal(expected: BtcRefusal, block: () -> Unit) {
        val refusal = assertFailsWith<BtcRefusedException> { block() }.refusal
        assertEquals(expected, refusal)
    }
}
