package app.lensly.core.btc

import fr.acinq.bitcoin.KeyPath

/**
 * What the owner sees before a Bitcoin request is signed: every input and every output, the fee, and which output is the
 * user's own Change Output. The fee is the verified input total minus the output total.
 */
data class BtcReview(
    val inputPolicy: InputVerificationPolicy,
    val inputs: List<BtcReviewInput>,
    val outputs: List<BtcReviewOutput>,
    /**
     * The input total minus the output total, in satoshis. Null while any input amount is unverified, because a fee is never
     * computed from amounts that the request declares alone.
     */
    val feeSats: Long?,
    /** True when the policy needs an explicit confirmation before signing, which is the case for unverified segwit amounts. */
    val requiresConfirmation: Boolean,
) {
    /** True when every input amount was verified against its previous transaction. */
    val allInputsVerified: Boolean
        get() = inputs.all { it.amountVerified }
}

/** One input of the request. [ownerPath] is the full path of the key that signs it, or null for an input this Account does not own. */
data class BtcReviewInput(
    val index: Int,
    val amountSats: Long,
    val amountVerified: Boolean,
    val ownerPath: KeyPath?,
)

/** One output of the request. Only a change-path address of this Account within the known index range is the user's own. */
data class BtcReviewOutput(
    val index: Int,
    /** The address the output pays, or null for a script with no address, such as OP_RETURN. */
    val address: String?,
    val amountSats: Long,
    val isOwnChange: Boolean,
    /** The change index when [isOwnChange] is true. */
    val changeIndex: Int?,
)

/** Why a Bitcoin request was refused. Each reason names the input it concerns, where one does. */
sealed class BtcRefusal {
    abstract val reason: String

    /** The Signing Source Policy is Companion only, and this request does not carry the Companion App's marker. */
    data object SourceExcluded : BtcRefusal() {
        override val reason: String = "Only the Companion App may ask for a Bitcoin signature, and this request is not from it."
    }

    /** The input has no previous transaction, or under the active policy its amount cannot be verified. */
    data class MissingPreviousTransaction(val inputIndex: Int) : BtcRefusal() {
        override val reason: String
            get() = "Input $inputIndex has no previous transaction, so its amount cannot be verified."
    }

    /** The previous transaction, or the witness_utxo, does not match the outpoint the input spends. */
    data class PreviousTransactionMismatch(val inputIndex: Int) : BtcRefusal() {
        override val reason: String
            get() = "Input $inputIndex's previous transaction does not match the output it spends."
    }

    /** The input spends a script type that this Account does not use. */
    data class ScriptTypeNotUsed(val inputIndex: Int) : BtcRefusal() {
        override val reason: String
            get() = "Input $inputIndex spends a script type this Account does not use."
    }

    /** The input's derivation names this wallet's key outside this Account. */
    data class InputOutsideAccount(val inputIndex: Int) : BtcRefusal() {
        override val reason: String
            get() = "Input $inputIndex belongs to a different account or branch of this wallet."
    }

    /** The input's derivation names this wallet's key, but the key at that path does not produce the spent script. */
    data class InconsistentOwnership(val inputIndex: Int) : BtcRefusal() {
        override val reason: String
            get() = "Input $inputIndex names a key of this wallet that does not produce the output it spends."
    }

    /** An input this Account signs uses a sighash type other than ALL, so outputs could change after signing. */
    data class SighashNotAll(val inputIndex: Int) : BtcRefusal() {
        override val reason: String
            get() = "Input $inputIndex is signed with a sighash type other than ALL."
    }

    /** An input this Account would sign is already finalized. */
    data class AlreadyFinalized(val inputIndex: Int) : BtcRefusal() {
        override val reason: String
            get() = "Input $inputIndex is already finalized."
    }

    /** The policy needs an explicit confirmation of unverified input amounts before signing. */
    data object ConfirmationRequired : BtcRefusal() {
        override val reason: String = "Some input amounts are unverified. Confirm them before signing."
    }

    /** The outputs add up to more than the verified inputs. */
    data object OutputsExceedInputs : BtcRefusal() {
        override val reason: String = "The outputs are worth more than the inputs."
    }

    /** No input of the request belongs to this Account. */
    data object NothingToSign : BtcRefusal() {
        override val reason: String = "No input of this request belongs to this Account."
    }

    /** The signing library refused an input that the review accepted. */
    data class SigningFailed(val inputIndex: Int, val detail: String) : BtcRefusal() {
        override val reason: String
            get() = "Input $inputIndex could not be signed: $detail"
    }
}

/** Thrown when a rule refuses a Bitcoin request. Nothing is signed when it is thrown. */
class BtcRefusedException(val refusal: BtcRefusal) : Exception(refusal.reason)
