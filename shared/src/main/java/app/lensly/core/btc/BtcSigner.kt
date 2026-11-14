package app.lensly.core.btc

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.psbt.Input
import fr.acinq.bitcoin.psbt.Output
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.utils.Either

/** Positions of the branch and the address index inside an account path m/purpose'/coin'/account'/branch/index. */
private const val BRANCH_LEVEL = 3
private const val INDEX_LEVEL = 4

/**
 * Reviews a Bitcoin request against the owner's two policies and signs the inputs this Account owns.
 *
 * [review] lists every input and output. [sign] reviews the request first, then signs only the inputs whose owner path lies
 * under this Account, each with the key at that path. A refused request throws [BtcRefusedException] and nothing is signed.
 */
class BtcSigner(
    private val account: BtcAccount,
    private val inputPolicy: InputVerificationPolicy = InputVerificationPolicy.STRICT,
    private val sourcePolicy: SigningSourcePolicy = SigningSourcePolicy.ANY,
) {
    /**
     * The review of [psbt]: every input with its verified amount and owner, every output with its own-change label, and the fee.
     * Throws [BtcRefusedException] when a rule refuses the request.
     */
    fun review(psbt: Psbt): BtcReview {
        if (sourcePolicy == SigningSourcePolicy.COMPANION_ONLY && !BtcPsbt.hasCompanionMarker(psbt)) {
            refuse(BtcRefusal.SourceExcluded)
        }
        val tx = psbt.global.tx
        val inputs = tx.txIn.mapIndexed { index, txIn ->
            val input = psbt.inputs[index]
            val spent = spentOutput(index, txIn.outPoint, input)
            if (scriptTypeOf(spent.script, input) != account.scriptType) refuse(BtcRefusal.ScriptTypeNotUsed(index))
            val owner = ownerOf(index, input, spent.script)
            if (owner != null && !sighashIsAll(input)) refuse(BtcRefusal.SighashNotAll(index))
            BtcReviewInput(index, spent.amountSats, spent.verified, owner)
        }
        // The user's own Change Output is a change-path address of this Account within the known index range. Any other
        // path or index, including every receive address, is an outside Address.
        val changeIndexByScript = (0 until BtcAccount.GAP_LIMIT).associate { index ->
            ByteVector(account.scriptPubKey(BtcAccount.CHANGE, index)).toHex() to index
        }
        val outputs = tx.txOut.mapIndexed { index, txOut ->
            val changeIndex = changeIndexByScript[txOut.publicKeyScript.toHex()]
            BtcReviewOutput(
                index = index,
                address = Bitcoin.addressFromPublicKeyScript(
                    account.network.chain.chainHash,
                    txOut.publicKeyScript.toByteArray(),
                ).right,
                amountSats = txOut.amount.sat,
                isOwnChange = changeIndex != null,
                changeIndex = changeIndex,
                claimsOwnKey = psbt.outputs.getOrNull(index)?.let { claimsFingerprint(it, account.masterFingerprint) } ?: false,
            )
        }
        val allVerified = inputs.all { it.amountVerified }
        val inputTotal = inputs.sumOf { it.amountSats }
        val outputTotal = outputs.sumOf { it.amountSats }
        if (allVerified && outputTotal > inputTotal) refuse(BtcRefusal.OutputsExceedInputs)
        return BtcReview(
            inputPolicy = inputPolicy,
            inputs = inputs,
            outputs = outputs,
            feeSats = if (allVerified) inputTotal - outputTotal else null,
            requiresConfirmation = inputPolicy == InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING && !allVerified,
            estimatedVsize = BtcFeeRate.estimatedVsize(tx, account.scriptType),
        )
    }

    /** True when an ECDSA or taproot derivation of [output] names the master key [fingerprint]. */
    private fun claimsFingerprint(output: Output, fingerprint: Long): Boolean =
        output.derivationPaths.values.any { it.masterKeyFingerprint == fingerprint } ||
            output.taprootDerivationPaths.values.any { it.masterKeyFingerprint == fingerprint }

    /**
     * Reviews [psbt] and then signs every input this Account owns. Returns the review and the PSBT with the new signatures.
     *
     * A policy that needs confirmation of unverified amounts refuses unless [unverifiedAmountsConfirmed] is true, which the caller
     * sets only after the owner has confirmed those amounts on screen.
     */
    fun sign(psbt: Psbt, unverifiedAmountsConfirmed: Boolean = false): BtcSignedPsbt {
        val review = review(psbt)
        if (review.requiresConfirmation && !unverifiedAmountsConfirmed) refuse(BtcRefusal.ConfirmationRequired)
        val owned = review.inputs.filter { it.ownerPath != null }
        if (owned.isEmpty()) refuse(BtcRefusal.NothingToSign)
        var signed = psbt
        for (input in owned) {
            val path = checkNotNull(input.ownerPath)
            if (signed.inputs[input.index].isFinalized()) refuse(BtcRefusal.AlreadyFinalized(input.index))
            val key = account.privateKey(path.path[BRANCH_LEVEL].toInt(), path.path[INDEX_LEVEL].toInt())
            signed = when (val result = signed.sign(key, input.index)) {
                is Either.Left -> refuse(BtcRefusal.SigningFailed(input.index, result.value.toString()))
                is Either.Right -> result.value.psbt
            }
        }
        return BtcSignedPsbt(review, signed)
    }

    private class Spent(val amountSats: Long, val verified: Boolean, val script: ByteArray)

    /**
     * The output an input spends. A previous transaction verifies the amount. A witness_utxo alone verifies nothing, and it is
     * accepted only under a weaker policy, for a segwit output.
     */
    private fun spentOutput(index: Int, outPoint: OutPoint, input: Input): Spent {
        val previous = input.nonWitnessUtxo
        val witness = input.witnessUtxo
        if (previous != null) {
            val spentIndex = outPoint.index
            if (previous.txid != outPoint.txid || spentIndex < 0 || spentIndex >= previous.txOut.size) {
                refuse(BtcRefusal.PreviousTransactionMismatch(index))
            }
            val output = previous.txOut[spentIndex.toInt()]
            if (witness != null && witness != output) refuse(BtcRefusal.PreviousTransactionMismatch(index))
            return Spent(output.amount.sat, verified = true, script = output.publicKeyScript.toByteArray())
        }
        val script = witness?.publicKeyScript?.toByteArray()
        if (witness == null || script == null || inputPolicy == InputVerificationPolicy.STRICT || !isSegwit(script, input)) {
            refuse(BtcRefusal.MissingPreviousTransaction(index))
        }
        return Spent(witness.amount.sat, verified = false, script = script)
    }

    /**
     * The path of the key that signs this input, or null when no derivation in the input names this wallet's master fingerprint.
     * A derivation that names the fingerprint must lie under this Account, and the key at its path must produce the spent script.
     */
    private fun ownerOf(index: Int, input: Input, script: ByteArray): KeyPath? {
        val claims = (
            input.derivationPaths.values.map { it.masterKeyFingerprint to it.keyPath } +
                input.taprootDerivationPaths.values.map { it.masterKeyFingerprint to it.keyPath }
            )
            .filter { (fingerprint, _) -> fingerprint == account.masterFingerprint }
            .map { (_, path) -> path }
            .distinct()
        if (claims.isEmpty()) return null
        if (claims.size > 1) refuse(BtcRefusal.InconsistentOwnership(index))
        val path = claims.single()
        if (path.path.size != 5 || path.path.take(3) != account.path.path) refuse(BtcRefusal.InputOutsideAccount(index))
        val branch = path.path[BRANCH_LEVEL]
        val child = path.path[INDEX_LEVEL]
        if (branch != 0L && branch != 1L) refuse(BtcRefusal.InputOutsideAccount(index))
        if (DeterministicWallet.isHardened(child)) refuse(BtcRefusal.InputOutsideAccount(index))
        if (!account.scriptPubKey(branch.toInt(), child.toInt()).contentEquals(script)) {
            refuse(BtcRefusal.InconsistentOwnership(index))
        }
        return path
    }

    /** ALL, or DEFAULT for taproot, which commits to every input and output the way ALL does. Other types leave room to change outputs. */
    private fun sighashIsAll(input: Input): Boolean {
        val sighash = input.sighashType ?: return true
        return sighash == SigHash.SIGHASH_ALL ||
            (account.scriptType == ScriptType.TAPROOT && sighash == SigHash.SIGHASH_DEFAULT)
    }

    private fun isSegwit(script: ByteArray, input: Input): Boolean =
        Script.isPay2wpkh(script) || Script.isPay2tr(script) || isNestedP2wpkh(script, input)

    private fun isNestedP2wpkh(script: ByteArray, input: Input): Boolean {
        val redeem = input.redeemScript ?: return false
        return Script.isPay2sh(script) &&
            Script.isPay2wpkh(redeem) &&
            Script.write(Script.pay2sh(redeem)).contentEquals(script)
    }

    private fun scriptTypeOf(script: ByteArray, input: Input): ScriptType? = when {
        Script.isPay2pkh(script) -> ScriptType.LEGACY
        Script.isPay2wpkh(script) -> ScriptType.NATIVE_SEGWIT
        Script.isPay2tr(script) -> ScriptType.TAPROOT
        isNestedP2wpkh(script, input) -> ScriptType.NESTED_SEGWIT
        else -> null
    }

    private fun refuse(reason: BtcRefusal): Nothing = throw BtcRefusedException(reason)
}

/** A signed request: the review the owner saw, and the PSBT with this Account's signatures added. */
data class BtcSignedPsbt(val review: BtcReview, val psbt: Psbt) {
    /** The signed PSBT as `ur:crypto-psbt`, the form the online side reads from a QR code. */
    val ur: String
        get() = BtcPsbt.encodeUr(psbt)
}

private fun Input.isFinalized(): Boolean =
    this is Input.FinalizedInputWithoutUtxo ||
        this is Input.WitnessInput.FinalizedWitnessInput ||
        this is Input.NonWitnessInput.FinalizedNonWitnessInput
