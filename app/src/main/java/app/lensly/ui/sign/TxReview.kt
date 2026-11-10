package app.lensly.ui.sign

import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry

/**
 * The [TxReview.function] of a personal message. A message has no recipient, amount, nonce or fee, and its text is
 * the only parameter, so the review screen shows the message instead of those facts.
 */
const val SIGN_MESSAGE_FUNCTION = "Sign message"

/** The network name of a chain that is not in the registry. Its coin is not known either. */
const val UNKNOWN_CHAIN_NAME = "Unknown Chain"

/** The name of a token that is not in the registry. */
const val UNKNOWN_TOKEN_NAME = "Unknown token"

/** The unit of an amount whose coin or token is not known. The amount is then shown as raw base units. */
const val UNKNOWN_UNIT = "base units"

/** The [TxReview.amountNote] of a token amount whose decimals are not known. */
const val UNKNOWN_DECIMALS_NOTE = "Decimals unknown: raw integer amount, not verified"

/**
 * Everything the review screen shows about one transaction before the user decides.
 *
 * [amount] is already formatted for display, for example "0.0100". [fee] holds label and value
 * pairs such as "Max fee" to "52.5 gwei". [params] holds decoded call arguments, and is empty for a
 * native transfer. [warnings] are shown first, in red. A chain or token outside the registry is shown
 * with [UNKNOWN_CHAIN_NAME] or [UNKNOWN_TOKEN_NAME] and raw base units, and [needsRegistryConfirmation]
 * is then true.
 */
data class TxReview(
    val networkName: String,
    val chainId: Long,
    val fromAccountLabel: String,
    val fromAddress: String,
    val toAddress: String,
    val amount: String,
    val symbol: String,
    val tokenContract: String?,
    val function: String,
    val params: List<Pair<String, String>>,
    val fee: List<Pair<String, String>>,
    val feeNote: String?,
    val warnings: List<String>,
    val nonce: Long,
    val txType: String,
    /** The network the review is for. It supplies the chain logo. Null when the chain is not in the registry. */
    val network: NetworkEntry? = null,
    /** The token that is moved or approved. It supplies the token logo. Null for a native transfer and a message. */
    val token: TokenEntry? = null,
    /**
     * The muted line under the amount. For an unknown chain it is the value under an assumed 18 decimals, marked as
     * an assumption. For an unknown token it says the decimals are unknown. Null for a known coin or token.
     */
    val amountNote: String? = null,
) {
    /**
     * True when the chain or the token is outside the registry. Such a review carries the registry warning, and APPROVE
     * waits for the user's check of the chain ID and contract. It follows from [network] and [token], so they cannot
     * disagree with it.
     */
    val needsRegistryConfirmation: Boolean
        get() = network == null || (tokenContract != null && token == null)

    /** True when APPROVE may be pressed: every fact has been seen and, outside the registry, the check is ticked. */
    fun canApprove(everyFactShown: Boolean, registryChecked: Boolean): Boolean =
        everyFactShown && (!needsRegistryConfirmation || registryChecked)
}
