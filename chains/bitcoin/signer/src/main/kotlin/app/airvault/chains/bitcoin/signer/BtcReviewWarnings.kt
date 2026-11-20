package app.airvault.chains.bitcoin.signer

/** The dust limit in satoshis: an output of an address below it costs more to spend than it holds, and nodes do not relay it. */
internal const val DUST_LIMIT_SATS = 546L

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

    /** Outputs that pay an address less than [DUST_LIMIT_SATS]. Nodes do not relay them, so the payment may never arrive. */
    data class DustOutputs(val count: Int) : BtcWarning()

    /** An input the online side marked as carrying an inscription or a rune. Spending it may burn that inscription or rune. */
    data class RiskMarkedInput(val index: Int) : BtcWarning()

    /** Inputs the online side did not mark, so their inscriptions and runes were not checked. Lensly cannot check them offline. */
    data object RiskUnchecked : BtcWarning()

    /** The fee rate, in satoshis per vbyte, is above the owner's limit of [thresholdSatPerVb]. */
    data class HighFeeRate(val satPerVb: Double, val thresholdSatPerVb: Int) : BtcWarning()

    /** The fee is [percent] per cent of the amount the request sends to others, above the owner's limit of [thresholdPercent]. */
    data class HighFeeShare(val percent: Double, val thresholdPercent: Int) : BtcWarning()

    /**
     * The transaction cannot confirm before [lockTime], a block height or a Unix time. The lock holds whenever it is enforced, so
     * the warning does not say whether that point has passed: the review works offline and cannot see the current block or time.
     */
    data class LockTime(val lockTime: Long) : BtcWarning()
}

/** The warnings of this review, in the order the review shows them. An empty list is a review with nothing to point out. */
fun BtcReview.warnings(): List<BtcWarning> = buildList {
    inputs.filter { it.riskMarked }.forEach { add(BtcWarning.RiskMarkedInput(it.index)) }
    if (inputs.any { !it.riskMarked }) add(BtcWarning.RiskUnchecked)
    val external = inputs.count { it.ownerPath == null }
    if (external > 0) add(BtcWarning.ExternalInputs(external))
    val unverified = inputs.count { !it.amountVerified }
    if (unverified > 0) add(BtcWarning.UnverifiedAmounts(unverified))
    val unknownChange = outputs.count { it.claimsOwnKey && !it.isOwnChange }
    if (unknownChange > 0) add(BtcWarning.UnknownChange(unknownChange))
    // An output with no address, such as OP_RETURN, is not a payment, so only the outputs with an address are checked.
    val dust = outputs.count { it.address != null && it.amountSats < DUST_LIMIT_SATS }
    if (dust > 0) add(BtcWarning.DustOutputs(dust))
    if (lockTimeEnforced) add(BtcWarning.LockTime(lockTime))
    addAll(feeWarnings)
}
