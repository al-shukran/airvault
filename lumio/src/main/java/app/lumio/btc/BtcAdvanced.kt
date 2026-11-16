package app.lumio.btc

/**
 * The fee rate of a Bitcoin send, edited on the advanced sheet. The request is rebuilt at the rate the owner keeps, with the same coins. RBF is
 * not a field: every input of a Lumio request signals replace-by-fee.
 */
object BtcAdvanced {
    /** A fee rate more than this many times the suggested one draws a warning. */
    const val FAR_ABOVE = 3

    private const val MAX_CHARS = 12
    private const val MAX_RATE = 1000.0
    private val RATE = Regex("\\d{1,8}(\\.\\d+)?")

    /** The fee rate of [text] in sat/vB: a number more than zero and at most 1000. Throws IllegalArgumentException with the words the field shows. */
    fun feeRate(text: String): Double {
        val rate = text.trim()
        require(rate.length <= MAX_CHARS && RATE.matches(rate)) { "Enter a fee rate in sat/vB, such as 2." }
        val value = rate.toDouble()
        require(value > 0.0) { "The fee rate must be more than zero." }
        require(value <= MAX_RATE) { "The fee rate must be at most 1000 sat/vB." }
        return value
    }

    /** What the owner should look at before signing at [rate] instead of [suggested]: a rate far above the suggested one. */
    fun warnings(suggested: Double, rate: Double): List<String> =
        if (rate > suggested * FAR_ABOVE) listOf("The fee rate is more than $FAR_ABOVE times the suggested rate.") else emptyList()
}
