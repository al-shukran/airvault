package app.lensly.core.btc

/**
 * A condition of a Bitcoin request that the owner reads on the review before signing. A warning is shown in the review, and it
 * does not stop signing by itself. The review stops signing until the owner confirms the unverified amounts, which is what
 * [BtcReview.requiresConfirmation] asks for. A refusal is different: a refused request is never reviewed.
 */
sealed class BtcWarning {
    /** Inputs that another signer owns. Lensly does not sign them, so their signatures must come from elsewhere. */
    data class ExternalInputs(val count: Int) : BtcWarning()

    /** Input amounts that come from a witness UTXO alone. They are not verified, so the fee is unknown until they are confirmed. */
    data class UnverifiedAmounts(val count: Int) : BtcWarning()

    /** Outputs that name a key of this wallet but do not pay this Account's change path. The owner should check where they go. */
    data class UnknownChange(val count: Int) : BtcWarning()
}

/** The warnings of this review, in the order the review shows them. An empty list is a review with nothing to point out. */
fun BtcReview.warnings(): List<BtcWarning> = buildList {
    val external = inputs.count { it.ownerPath == null }
    if (external > 0) add(BtcWarning.ExternalInputs(external))
    val unverified = inputs.count { !it.amountVerified }
    if (unverified > 0) add(BtcWarning.UnverifiedAmounts(unverified))
    val unknownChange = outputs.count { it.claimsOwnKey && !it.isOwnChange }
    if (unknownChange > 0) add(BtcWarning.UnknownChange(unknownChange))
}
