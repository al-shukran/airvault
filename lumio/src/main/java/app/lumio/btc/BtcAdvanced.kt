package app.lumio.btc

/**
 * The fee rate of a Bitcoin send, edited on the advanced sheet. The rate is checked by the form's own rule ([BtcSendInput.feeRate]: 1 to 1000
 * sat/vB), and the request is rebuilt at the rate the owner keeps, with the same coins. RBF is not a field: every input of a Lumio request
 * signals replace-by-fee.
 */
object BtcAdvanced {
    /** A fee rate more than this many times the suggested one draws a warning. */
    const val FAR_ABOVE = 3

    /** What the owner should look at before signing at [rate] instead of [suggested]: a rate far above the suggested one. */
    fun warnings(suggested: Double, rate: Double): List<String> =
        if (rate > suggested * FAR_ABOVE) listOf("The fee rate is more than $FAR_ABOVE times the suggested rate.") else emptyList()
}
