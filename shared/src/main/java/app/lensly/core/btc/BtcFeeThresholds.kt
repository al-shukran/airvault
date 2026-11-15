package app.lensly.core.btc

/**
 * The owner's fee limits for a Bitcoin review. A review shows a WARN notice when its fee rate is above [highFeeRateSatPerVb], and
 * when its fee is above [feeSharePercent] per cent of the amount the request sends to others. A value on the limit is not above
 * it. A warning does not stop signing: the owner reads it on the review, as the other review warnings are read.
 */
data class BtcFeeThresholds(
    /** The fee rate above which the review warns, in satoshis per vbyte. */
    val highFeeRateSatPerVb: Int,
    /** The fee above which the review warns, as a per cent of the amount the request sends to others. */
    val feeSharePercent: Int,
) {
    /**
     * The WARN notices of a review whose fee is [feeSats], whose outputs that are not the owner's change send [paidSats] in all, and
     * whose estimated size is [estimatedVsize] vbytes. A fee rate is checked against the estimate, as the review shows it.
     */
    fun warnings(feeSats: Long, paidSats: Long, estimatedVsize: Long): List<BtcWarning> = buildList {
        if (estimatedVsize > 0 && feeSats > highFeeRateSatPerVb.toLong() * estimatedVsize) {
            add(BtcWarning.HighFeeRate(feeSats.toDouble() / estimatedVsize, highFeeRateSatPerVb))
        }
        // A request that sends nothing to others has no amount to take a share of, so only the fee rate is checked for it.
        if (paidSats > 0 && feeSats * PER_CENT > feeSharePercent.toLong() * paidSats) {
            add(BtcWarning.HighFeeShare(feeSats.toDouble() * PER_CENT / paidSats, feeSharePercent))
        }
    }

    companion object {
        /** The lowest fee rate a limit may be set to, in satoshis per vbyte. */
        const val MIN_RATE_SAT_PER_VB = 1

        /** The highest fee rate a limit may be set to, in satoshis per vbyte. */
        const val MAX_RATE_SAT_PER_VB = 10_000

        /** The lowest fee share a limit may be set to, in per cent. */
        const val MIN_SHARE_PERCENT = 1

        /** The highest fee share a limit may be set to, in per cent. */
        const val MAX_SHARE_PERCENT = 100

        /** The limits the core starts from: 200 sat/vB, and a tenth of the amount sent. */
        val DEFAULT = BtcFeeThresholds(highFeeRateSatPerVb = 200, feeSharePercent = 10)

        private const val PER_CENT = 100L

        /** The limits for the two values, or null when either is outside its range. */
        fun of(highFeeRateSatPerVb: Int, feeSharePercent: Int): BtcFeeThresholds? =
            if (highFeeRateSatPerVb in MIN_RATE_SAT_PER_VB..MAX_RATE_SAT_PER_VB && feeSharePercent in MIN_SHARE_PERCENT..MAX_SHARE_PERCENT) {
                BtcFeeThresholds(highFeeRateSatPerVb, feeSharePercent)
            } else {
                null
            }
    }
}
