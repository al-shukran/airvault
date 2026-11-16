package app.lumio.evm

import java.math.BigDecimal
import java.math.BigInteger

/** The fields of the advanced sheet of an Ethereum send, as the owner types them. The fees are in gwei. */
data class EvmAdvancedFields(val nonce: String, val gasLimit: String, val maxFeeGwei: String, val maxPriorityGwei: String)

/** The fields that [EvmAdvanced.check] can refuse. */
enum class EvmField { NONCE, GAS_LIMIT, MAX_FEE, MAX_PRIORITY }

/** The result of checking the owner's edits: the send they make with its warnings, or the words for each field that is refused. */
sealed interface EvmAdvancedCheck {
    class Valid(val send: PreparedSend, val warnings: List<String>) : EvmAdvancedCheck

    class Invalid(val errors: Map<EvmField, String>) : EvmAdvancedCheck
}

/**
 * The nonce, the gas limit and the EIP-1559 fees of an Ethereum send, edited on the advanced sheet. The values are checked against the
 * suggested send that the network reads prepared. Lumio builds only EIP-1559 transactions, so there is no legacy gas price to edit.
 */
object EvmAdvanced {
    /** A fee more than this many times the suggested one draws a warning. */
    const val FAR_ABOVE = 3L

    private const val GWEI_DECIMALS = 9
    private const val MAX_CHARS = 40
    private val NUMBER = Regex("\\d{1,19}")
    private val GWEI = Regex("\\d{1,20}(\\.\\d+)?")

    /** The fields of [send] as the sheet shows them: the nonce and the gas limit whole, and the fees in gwei. */
    fun fieldsOf(send: PreparedSend): EvmAdvancedFields = EvmAdvancedFields(
        nonce = send.nonce.toString(),
        gasLimit = send.gasLimit.toString(),
        maxFeeGwei = gwei(send.maxFeePerGas),
        maxPriorityGwei = gwei(send.maxPriorityFeePerGas),
    )

    /**
     * Applies [fields] to [suggested]. Each refused field carries the words the sheet shows under it. A send whose most cost is more than the
     * balance is refused on the fee cap. A valid send comes with its warnings.
     */
    fun check(suggested: PreparedSend, fields: EvmAdvancedFields): EvmAdvancedCheck {
        val errors = LinkedHashMap<EvmField, String>()
        val nonce = field(errors, EvmField.NONCE) { nonceOf(fields.nonce) }
        val gasLimit = field(errors, EvmField.GAS_LIMIT) { gasLimitOf(fields.gasLimit) }
        val maxFee = field(errors, EvmField.MAX_FEE) { feeCapOf(fields.maxFeeGwei) }
        val priority = field(errors, EvmField.MAX_PRIORITY) { gweiOf(fields.maxPriorityGwei) }
        if (maxFee != null && priority != null && priority > maxFee) {
            errors[EvmField.MAX_PRIORITY] = "The priority fee cannot be more than the fee cap."
        }
        if (errors.isNotEmpty() || nonce == null || gasLimit == null || maxFee == null || priority == null) return EvmAdvancedCheck.Invalid(errors)
        val send = suggested.withFees(nonce, gasLimit, maxFee, priority)
        if (send.maxCostWei > send.balanceWei) {
            return EvmAdvancedCheck.Invalid(
                mapOf(
                    EvmField.MAX_FEE to "The balance is ${EvmFormat.amount(send.balanceWei, send.chain.decimals, send.chain.symbol)}, " +
                        "but this send can cost up to ${EvmFormat.amount(send.maxCostWei, send.chain.decimals, send.chain.symbol)} at these fees.",
                ),
            )
        }
        return EvmAdvancedCheck.Valid(send, warnings(suggested, send))
    }

    /** What the owner should look at before signing [send] instead of [suggested]: a nonce below the pending one, and a fee far above the suggested one. */
    fun warnings(suggested: PreparedSend, send: PreparedSend): List<String> = buildList {
        if (send.nonce < suggested.nonce) {
            add("The nonce is below the pending nonce ${suggested.nonce}. This transaction replaces a pending one.")
        }
        if (farAbove(send.maxFeePerGas, suggested.maxFeePerGas) || farAbove(send.maxPriorityFeePerGas, suggested.maxPriorityFeePerGas)) {
            add("The fee is more than $FAR_ABOVE times the suggested fee.")
        }
    }

    private fun farAbove(value: BigInteger, suggested: BigInteger): Boolean = value > suggested * BigInteger.valueOf(FAR_ABOVE)

    private fun nonceOf(text: String): Long {
        val nonce = text.trim().takeIf { NUMBER.matches(it) }?.toLongOrNull()
        require(nonce != null) { "Enter the nonce as a whole number, 0 or more." }
        return nonce
    }

    private fun gasLimitOf(text: String): BigInteger {
        val gas = text.trim().takeIf { NUMBER.matches(it) }?.toBigIntegerOrNull()
        require(gas != null) { "Enter the gas limit as a whole number." }
        require(gas >= BigInteger.valueOf(EvmSend.TRANSFER_GAS)) { "A transfer needs at least ${EvmSend.TRANSFER_GAS} gas." }
        return gas
    }

    /** The fee cap: a fee in gwei that is more than zero. */
    private fun feeCapOf(text: String): BigInteger {
        val cap = gweiOf(text)
        require(cap.signum() > 0) { "The fee cap must be more than zero." }
        return cap
    }

    /** A fee in gwei as wei. It has at most 9 decimal places, which is one wei. */
    private fun gweiOf(text: String): BigInteger {
        val value = text.trim()
        require(value.length <= MAX_CHARS && GWEI.matches(value)) { "Enter the fee in gwei, such as 30." }
        val gwei = BigDecimal(value)
        require(gwei.stripTrailingZeros().scale() <= GWEI_DECIMALS) { "Use at most $GWEI_DECIMALS decimal places for gwei." }
        return gwei.movePointRight(GWEI_DECIMALS).toBigIntegerExact()
    }

    private fun gwei(wei: BigInteger): String = BigDecimal(wei).movePointLeft(GWEI_DECIMALS).stripTrailingZeros().toPlainString()

    /** Runs [parse]. A refusal is recorded under [key] and the value is null, so the other fields are still checked. */
    private inline fun <T : Any> field(errors: MutableMap<EvmField, String>, key: EvmField, parse: () -> T): T? = try {
        parse()
    } catch (e: IllegalArgumentException) {
        errors[key] = e.message.orEmpty()
        null
    }
}
